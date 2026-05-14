# Ревью Managers — РЕАЛИЗАЦИЯ ОТСУТСТВУЕТ

Источник: [docs/managers-api.md](../../docs/managers-api.md)  
Код: **нет**

---

## Состояние

Эндпоинты, определённые в docs:

| Method   | Path                    | Doc                                    | Реализация     |
| -------- | ----------------------- | -------------------------------------- | -------------- |
| `GET`    | `/api/v1/managers`      | Список менеджеров, привязанных к OWNER | ❌ Отсутствует |
| `DELETE` | `/api/v1/managers/{id}` | Исключить менеджера из команды         | ❌ Отсутствует |

Связанные компоненты docs:

- `ManagerModel` (`id`, `name`, `username`, `lastSeenAt`) — DTO нет
- Коды ошибок `MANAGER_NOT_FOUND`, `HAS_ACTIVE_SESSION`, `FORBIDDEN` — `MANAGER_NOT_FOUND` используется в Reports, но не зарегистрирован в MESSAGES

Затронутые экраны мобилы:

- «Managers» — пусто или 404
- Экран генерации invite-кода работает, но эндпоинта, чтобы посмотреть созданного менеджера, нет
- Поток удаления менеджера отсутствует полностью

---

## Анализ пробелов

### Пробелы в схеме

Для списка/удаления менеджеров нужны изменения связанных сущностей:

1. **`User.owner`** — к какому владельцу привязан менеджер? В схеме нет (см. [01-auth-review.md](01-auth-review.md) #8).
2. **`User.username`** — Docs описывают `username: string (без '@')`. В сущности нет (см. [04-reports-review.md](04-reports-review.md) #7).
3. **`User.lastSeenAt`** — время логина/refresh или последнего авторизованного запроса. В схеме нет. Предложение: `JwtAuthFilter` после успешной проверки токена обновляет `user.lastSeenAt = now` (с троттлингом 5 мин, чтобы не писать на каждый запрос).

### Логика эндпоинтов (предложение)

**`GET /api/v1/managers`** — OWNER-only.

```java
@GetMapping("/api/v1/managers")
public ResponseEntity<List<ManagerResponse>> list(@AuthenticationPrincipal User user) {
    if (user.getRole() != User.Role.OWNER) throw new AppException("FORBIDDEN", FORBIDDEN);
    return ResponseEntity.ok(managerService.listByOwner(user));
}
```

Сортировка: docs не уточняют, рекомендую `lastSeenAt DESC NULLS LAST, name ASC`.

**`DELETE /api/v1/managers/{id}`** — OWNER-only.

По docs, при попытке удалить менеджера с активной сессией опционально отдавать `409 HAS_ACTIVE_SESSION`. Реализация:

```java
@Transactional
public void delete(User owner, UUID managerId) {
    User manager = userRepository.findById(managerId)
        .filter(m -> m.getRole() == Role.MANAGER)
        .filter(m -> m.getOwner() != null && m.getOwner().getId().equals(owner.getId()))
        .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", NOT_FOUND));

    if (sessionRepository.existsByManagerAndIsActiveTrue(manager)) {
        throw new AppException("HAS_ACTIVE_SESSION", CONFLICT);  // опционально
    }
    // Рекомендую soft-delete (для исторических отчётов)
    manager.setDeletedAt(Instant.now());
    userRepository.save(manager);
}
```

> Замечание: в текущей сущности `User` нет `deletedAt` — добавить. Soft-delete не должен ломать запросы Reports (`findSessionLog`); FK `session.manager` должен продолжать ссылаться на удалённого пользователя.

---

## Дорожная карта реализации

### Шаг 1 — Миграция схемы

В сущность `User` добавить:

```java
@ManyToOne(fetch = LAZY) @JoinColumn(name = "owner_id")
private User owner;

@Column(unique = true)
private String username;

private Instant lastSeenAt;

private Instant deletedAt;
```

В `AuthServiceImpl.register` генерировать username (например, local-part email + суффикс), для MANAGER устанавливать `owner` из InviteCode.

При `ddl-auto: update` поля автоматически добавятся `ADD COLUMN`, но для уже существующих MANAGER `owner_id` и `username` будут NULL → нужна backfill-миграция. Отсутствие Flyway/Liquibase здесь больно бьёт (см. [08-config-build-review.md](08-config-build-review.md)).

### Шаг 2 — DTO

```java
@Data @Builder
public class ManagerResponse {
    private String id;
    private String name;
    private String username;
    private Instant lastSeenAt;
}
```

### Шаг 3 — Controller + Service + Repository

`ManagersController` на `/api/v1/managers`, интерфейс `ManagerService` + impl, в `UserRepository`:

```java
List<User> findByRoleAndOwnerAndDeletedAtIsNullOrderByLastSeenAtDesc(Role role, User owner);
```

### Шаг 4 — Добавить коды ошибок в MESSAGES

`MANAGER_NOT_FOUND`, `HAS_ACTIVE_SESSION` (в Reports и Session этот код пока не используется, но для Managers понадобится — не путать с `TABLE_HAS_ACTIVE_SESSION` для Session; это отдельный код).

### Шаг 5 — Механизм обновления last-seen

В `JwtAuthFilter`:

```java
Instant last = user.getLastSeenAt();
if (last == null || ChronoUnit.MINUTES.between(last, Instant.now()) > 5) {
    user.setLastSeenAt(Instant.now());
    userRepository.save(user);
}
```

UPDATE на каждый запрос — дорого. Троттлинг 5 мин. Альтернатива — асинхронная запись через event publisher.

### Шаг 6 — Связать с subscription gate

Удаление менеджера — read-only или write? Таблица из (subscription-api.md):

> | Manager invite | 403 SUBSCRIPTION_REQUIRED |

Таблицы для удаления нет, но если квалифицируем «как write» — нужно применить gate. Решать с командой.

---

## Влияние на мобилу

Предположим, мобильный пакет `packages/managers` уже написан по docs. Без backend-реализации:

- Экран списка менеджеров видит пустой массив или 404 (404 — корректно).
- Экран детального просмотра менеджера (отличается от Reports `/managers/{id}`) не нужен — `managers-api.md` detail-эндпоинт не определяет.
- Поток удаления не работает.

Временный workaround: backend на `/api/v1/managers` возвращает 501 Not Implemented, мобила показывает empty state «Coming soon». Либо реализовать эндпоинты сразу в MVP.

---

## Оценка трудозатрат

Изменения схемы + 2 эндпоинта + DTO + механизм last-seen:

- Backend — полдня (senior)
- Скрипт миграции — полдня (с добавлением Flyway)
- Написание тестов — 2 часа
- Итого: ~1 день

Эту задачу можно делать в одной миграции вместе с **04-reports-review.md** #7 (поле `username`) и **01-auth-review.md** #8 (поле `User.owner`) — суммарно ~1.5 дня.
