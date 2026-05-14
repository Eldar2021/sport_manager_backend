# Ревью Session

Источник: [docs/session_api.md](../../docs/session_api.md)  
Код: [SessionController](../../src/main/java/kg/sportmanager/controller/SessionController.java), [SessionServiceImpl](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java), [Session](../../src/main/java/kg/sportmanager/entity/Session.java), [SessionMapper](../../src/main/java/kg/sportmanager/util/SessionMapper.java), [SessionRepository](../../src/main/java/kg/sportmanager/repository/SessionRepository.java)

---

## Матрица соответствия эндпоинтов

| Doc                                | Код   | Auth Doc        | Код                      | Response      | Соответствие |
| ---------------------------------- | ----- | --------------- | ------------------------ | ------------- | ------------ |
| `POST /api/v1/session/start`       | то же | Both            | `validateTableAccess` ⚠️ | SessionLite   | ⚠️           |
| `POST /api/v1/session/{id}/pause`  | то же | Both            | ⚠️ + баг идемпотентности | SessionLite   | ⚠️           |
| `POST /api/v1/session/{id}/resume` | то же | Both            | ⚠️                       | SessionLite   | ⚠️           |
| `POST /api/v1/session/{id}/finish` | то же | Both            | ⚠️                       | SessionResult | ⚠️           |
| `POST /api/v1/session/{id}/cancel` | то же | Both (окно 60s) | ✅ window                | SessionResult | ⚠️           |

Поле `managerId` отсутствует в DTO (детали ниже).

---

## P0 — Критическая безопасность

### 1. Менеджеры могут манипулировать сессиями других владельцев

[SessionServiceImpl.validateTableAccess:240-252](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252):

```java
boolean hasAccess = switch (user.getRole()) {
    case OWNER -> owner.getId().equals(user.getId());
    case MANAGER -> true;   // ← BUG
};
```

В комментарии так и написано: «Для MANAGER нужно правильно по схеме, сейчас всегда true». Итог:

- Менеджер делает `POST /session/start { tableId: <стол чужого владельца> }` → 200, сессия запущена.
- Если менеджер узнает sessionId (из логов или ещё откуда) → может вызвать `pause`, `resume`, `finish`, `cancel`.
- Через `finish` можно обнулить чужую выручку (discount=100).

**Утечка данных + саботаж в multi-tenant.** Не должно попасть в прод.

**Исправление** (после починки связи manager↔owner):

```java
private void validateTableAccess(User user, Tables table) {
    User tableOwner = table.getVenue().getOwner();
    User userOwner = user.getRole() == User.Role.OWNER ? user : user.getOwner();
    if (userOwner == null || !tableOwner.getId().equals(userOwner.getId())) {
        throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
    }
}
```

### 2. Race condition в start

[SessionServiceImpl.start:51-69](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L51-L69):

```java
if (sessionRepository.existsByTableAndIsActiveTrue(table)) {
    throw new AppException("TABLE_HAS_ACTIVE_SESSION", ...);
}
Instant now = Instant.now();
Session session = Session.builder()...build();
sessionRepository.save(session);
```

Check-then-act. Изоляция по умолчанию READ_COMMITTED. Два параллельных `/start` на один стол:

1. T1: exists-check → false
2. T2: exists-check → false (T1 ещё не закоммитился)
3. T1: insert
4. T2: insert
5. Две ACTIVE-сессии на одном столе → весь lifecycle ломается (какую паузить?)

Docs [session_api.md §1 Race condition](../../docs/session_api.md#1-start-session):

> Backend блокирует стол в транзакции. На два параллельных start один проходит, второй получает 409.

**Исправление A** — pessimistic lock:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Tables t WHERE t.id = :id AND t.deletedAt IS NULL")
Optional<Tables> findByIdForUpdate(@Param("id") UUID id);
```

**Исправление B** — partial unique constraint в БД:

```sql
CREATE UNIQUE INDEX one_active_session_per_table ON sessions (table_id) WHERE is_active = true;
```

B проще и надёжнее — гарантия на стороне БД.

### 3. Идемпотентность pause — `pausedAt` перезаписывается

[SessionServiceImpl.pause:79-100](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L79-L100):

```java
if (session.getStatus() != Session.SessionStatus.ACTIVE) {
    throw new AppException("SESSION_NOT_ACTIVE", ...);
}
session.setPaused(true);
session.setPausedAt(now);
```

В enum нет `PAUSED` — только `ACTIVE/COMPLETED/CANCELLED`. При pause `status` остаётся ACTIVE, выставляется флаг `isPaused=true`. **Второй вызов pause:**

1. `findActiveSession` → isActive=true ✓
2. `status != ACTIVE` → false (всё ещё ACTIVE)
3. SESSION_NOT_ACTIVE не выбрасывается
4. `setPausedAt(now)` → **затирает прежний pausedAt**
5. Промежуток `prevPausedAt - now` теряется → бесплатная пауза

Если пользователь (или сломанный клиент) дважды нажмёт pause — счётчик неверный. Исправление: сначала проверять флаг `isPaused`:

```java
if (session.isPaused()) {
    throw new AppException("SESSION_ALREADY_PAUSED", HttpStatus.CONFLICT);
}
```

Имя `SESSION_ALREADY_PAUSED` vs `SESSION_NOT_ACTIVE` — вопрос семантики.

### 4. В `cancel` неверный код ошибки для уже отменённой сессии

[SessionServiceImpl.findActiveSession:224-233](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L224-L233):

```java
if (!session.isActive()) {
    throw new AppException("SESSION_ALREADY_COMPLETED", HttpStatus.CONFLICT);
}
```

Та же ошибка летит и для уже отменённой сессии — пользователь видит «уже завершена», хотя на деле она отменена. UX-неточность. Разделить по статусу:

```java
if (session.getStatus() == COMPLETED) throw new AppException("SESSION_ALREADY_COMPLETED", ...);
if (session.getStatus() == CANCELLED) throw new AppException("SESSION_ALREADY_CANCELLED", ...);
```

(Новый код добавить в `MESSAGES`.)

---

## P1 — Высокий

### 5. В DTO нет `managerId`

В docs `SessionLite` и `SessionResult`:

```ts
{
  id: ...,
  tableId: ...,
  managerId: string (uuid),  // ← кто начал
  ...
}
```

В [SessionLiteResponse](../../src/main/java/kg/sportmanager/dto/response/SessionLiteResponse.java) и [SessionResultResponse](../../src/main/java/kg/sportmanager/dto/response/SessionResultResponse.java) поля `managerId` **нет**. В сущности `manager` заполняется ([SessionServiceImpl.start:65](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L65)), но в ответе не отдаётся.

Итог: для экрана Reports/перформанса менеджеров критично — клиент не видит, кто запустил сессию (кроме списка в Reports).

Исправление: в обе DTO добавить `UUID managerId`, заполнять в маппере.

### 6. `SessionResponse` (на карточке стола на Home) не несёт `managerId` и `status`

[SessionResponse](../../src/main/java/kg/sportmanager/dto/response/SessionResponse.java) — отдельный DTO, который отдаётся в карточке стола на Home. Поля:

```java
private boolean isActive;
private boolean isPaused;
// нет status-строки, нет managerId
```

В docs [home_page_api.md § Session](../../docs/home_page_api.md#session) `managerId` обязателен. **Поправить HomeMapper.toSessionResponse + добавить `managerId` в `SessionResponse`.**

Идеальный вариант: убрать `SessionResponse`, использовать `SessionLiteResponse`. Два DTO с разной типизацией дат (string vs Instant) — лишнее.

### 7. Cancel reason 1-200 — нижняя граница проверяется вручную

[SessionServiceImpl.cancel:190-193](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L190-L193):

```java
if (request.getReason() == null || request.getReason().isBlank()
        || request.getReason().length() > 200) {
    throw new AppException("VALIDATION_ERROR", ...);
}
```

OK, но через Jakarta `@NotBlank @Size(max=200)` чище. Текущее решение приемлемо.

### 8. Risk Integer overflow для `subtotal` в `finish`

[SessionServiceImpl.finish:160](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L160):

```java
int subtotal = calculateSubtotal(billableSeconds, session.getTarifAmountSnapshot(), session.getTarifTypeSnapshot());
```

`calculateSubtotal` возвращает `int`. Worst case: год сессия (86400×365 ≈ 31.5M сек) × tarifAmount 1M / 86400 (DAY) ≈ 365M. Integer max 2.1B — OK. Но при tarifAmount=1M + DAY + сильно длинной сессии переполнение возможно. На практике не встречается, но для защиты лучше `long`.

`totalAmount` в сущности уже `Long` — OK.

### 9. Округление в `finish` дублируется

[SessionServiceImpl.finish:172](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L172):

```java
int discountAmount = Math.round(subtotal * discountPercent / 100f);
```

[SessionMapper.toResult:36](../../src/main/java/kg/sportmanager/util/SessionMapper.java#L36):

```java
int discountAmount = Math.round(subtotal * discountPercent / 100f);
```

Одна и та же формула в двух местах. Маппер пересчитывает заново. **Считать в одном месте** — пусть это делает сервис, маппер только копирует поля.

### 10. Pause-длительность через `getEpochSecond()` — теряются миллисекунды

[SessionServiceImpl.resume:120](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L120):

```java
long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
```

Docs «в секундах» → прагматично OK. Но пауза 0.6s засчитается как 0s. Суммарно за длинную сессию недосчёт пары секунд. Это в пользу клиента, против владельца. На практике пренебрежимо.

### 11. Cleanup pause в `finish`

[SessionServiceImpl.finish:148-153](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L148-L153):

```java
int totalPausedSeconds = session.getTotalPausedSeconds();
if (session.isPaused() && session.getPausedAt() != null) {
    long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
    totalPausedSeconds += (int) pausedDuration;
}
```

Docs: «Если PAUSED — автоматически resume, потом finish». Код всё считает правильно (пауза добавляется к totalPausedSeconds), но `setResumedAt(now)` не вызывает — только `setPaused(false)`. Для аудита `resumedAt` стоит выставлять. На практике вреда нет.

---

## P2 — Средний

### 12. `endedAt` в `cancel` устанавливается в нужный момент?

[SessionServiceImpl.cancel:207](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L207): `session.setEndedAt(now)` ✓. Пример из docs:

```json
"startedAt": "2026-04-27T18:42:00.000Z",
"endedAt": "2026-04-27T18:42:30.000Z"
```

OK. ✅

### 13. `setStatus(ACTIVE)` в `pause` — лишний

[SessionServiceImpl.pause:92](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L92): `session.setStatus(Session.SessionStatus.ACTIVE)` — уже было ACTIVE. Dead code. Удалить.

### 14. `setResumedAt(now)` в `resume` перезаписывается каждым resume

Первый resume — `resumedAt=T1`, второй — `resumedAt=T2` → теряется аудит. В мульти-pause/resume сценарии непонятно, какой именно resume. Docs не отдают это поле в ответе, только состояние. OK, но для истории пауз нужна отдельная таблица (docs предлагают: история пауз хранится на бэкенде). Вне MVP.

### 15. `SessionLiteResponse` `tarifTypeSnapshot` — enum, не строка

[SessionLiteResponse.java:30](../../src/main/java/kg/sportmanager/dto/response/SessionLiteResponse.java#L30):

```java
private Tables.TarifType tarifTypeSnapshot;
```

Jackson сериализует enum как `"HOUR"`. Docs: `"HOUR" | "MINUTE" | "DAY"` → OK. ✅

### 16. Логика null для `discountPercent` в `toResult`

[SessionMapper:48](../../src/main/java/kg/sportmanager/util/SessionMapper.java#L48):

```java
.discountPercent(s.getStatus() == Session.SessionStatus.COMPLETED ? discountPercent : null)
```

OK. Для CANCELLED — null, совпадает с docs. ✅

### 17. Если `finish` приходит на отменённую сессию

`findActiveSession` отклоняет `isActive=false`. У CANCELLED `isActive=false`. OK, лишняя защита. ✅

### 18. Может ли `endedAt - startedAt` быть отрицательным?

`now` это server time ≥ `startedAt` (бэкенд сам пишет). Edge: NTP-коррекция назад. `Math.max(0, ...)` уже стоит (строка 157). ✅

---

## P3 — Низкий

- `CANCEL_WINDOW_SECONDS = 60L` static final — OK.
- `SessionRepository.findCompletedByTableAndRange` и `ReportsRepository.findCompletedByTableAndRange` — **одинаковый запрос в двух местах**. Объединить через наследование репозиториев или общий интерфейс.
- `SessionRepository.findSessionLogByManager` хардкодит LIMIT 40 — параллельный запрос в ReportsRepository принимает параметр. Дубль. Оставить тот, что в ReportsRepository, этот удалить.
- `SessionServiceImpl.parseUuid` отдаёт `VALIDATION_ERROR` — для path-параметра подходит 400, но OK.
- `SessionStatus.ACTIVE` enum + флаг `isActive` — избыточно. `isActive=true ↔ status in (ACTIVE)`. Свести к одному полю.
- Комментарии — на русском, что согласуется с остальным кодом.

---

## Рекомендации

1. **Изоляция менеджеров между владельцами** — добавить `User.owner`, починить `validateTableAccess`.
2. **Гарантия одной активной сессии на стол** — partial unique index.
3. **Идемпотентность `pause`** — проверка `isPaused`.
4. **`managerId` во всех DTO сессий** — SessionLite, SessionResult, SessionResponse.
5. **Разделить коды Cancelled vs Completed** для уже завершённых.
6. **Объединить `SessionResponse` и `SessionLiteResponse`** — карточка стола на Home пусть использует SessionLite.
7. **Считать discount в одном месте** — либо сервис, либо маппер.
8. **Отдельная таблица истории пауз** (опционально, v2).
