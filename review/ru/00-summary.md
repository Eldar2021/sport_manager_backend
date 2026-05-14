# Общий обзор ревью

Дата: 2026-05-14  
Ревьюер: Claude (сравнение кода и документации)  
Область: текущее состояние ветки `develop` vs контракты `docs/*.md`

---

## Оценка

| Область            | Балл | Комментарий                                                                                             |
| ------------------ | ---- | ------------------------------------------------------------------------------------------------------- |
| Auth               | 5/10 | Эндпоинты есть, но непоследовательный формат ошибок, нет префикса `/api/v1`, forgot-password — заглушка |
| Home (Venue/Table) | 6/10 | В основном работает; связь manager→owner полностью сломана                                              |
| Session            | 5/10 | Логика верная, но **2 критические уязвимости**: cross-tenant доступ менеджера + race condition          |
| Reports            | 4/10 | Эндпоинты есть, но **баг NPE**, N+1 запросы, утечка данных менеджеров между владельцами                 |
| Managers API       | 0/10 | **Совсем не реализовано**                                                                               |
| Subscription API   | 0/10 | **Совсем не реализовано**, gate нигде не применяется                                                    |
| Security & i18n    | 4/10 | JWT работает; secret в репозитории, пароли в логах, перевода для большинства кодов ошибок нет           |
| Config & Build     | 3/10 | Hardcoded secret, нет env-var, нет Flyway, **тестов нет**, ddl-auto=update                              |

**Итог:** Код на уровне junior-разработчика — работает по «happy path», но не готов к продакшену. **Мобильный клиент, реализующий по docs, сломается** (несоответствие префикса + 2 отсутствующих API + несогласованный формат ошибок).

---

## Топ-10 критичных находок (по приоритету)

### P0 — Должно быть исправлено до прода

1. **Cross-tenant манипуляция сессиями** — [SessionServiceImpl.validateTableAccess](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252) для роли MANAGER возвращает `case MANAGER -> true`. Любой менеджер может pause/resume/finish/cancel **любую сессию** в системе (нужно угадать sessionId, но проверки владельца нет). Аналогично, может стартовать сессию на столе чужого владельца. **Multi-tenant утечка данных.**

2. **Subscription API и gate отсутствуют полностью** — [docs/subscription-api.md](../../docs/subscription-api.md) описывает 5 эндпоинтов и правило «EXPIRED owner → 403 SUBSCRIPTION_REQUIRED». В бэкенде нет ни контроллеров/сервисов, ни сущностей (`Subscription`, `Payment`). Поток оплаты не работает, механизма блокировки нет → бесплатный SaaS.

3. **Managers API отсутствует полностью** — нет `GET /api/v1/managers`, `DELETE /api/v1/managers/{id}`. Экран списка и удаления менеджеров в мобильном приложении сломан.

4. **NPE в `ManagerDetailResponse`** — [ReportsServiceImpl.mapToSessionLogEntry](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L583): `Long.valueOf(s.getDurationSeconds())` — `durationSeconds` равен null для CANCELLED-сессий. Если в истории менеджера есть отменённые сессии — эндпоинт даёт 500. (`Long.valueOf` принимает Integer + autounbox → NPE.)

5. **В схеме нет связи Manager↔Owner** — у сущности `User` нет поля `owner`. Хотя приглашающий хранится в `InviteCode.owner`, при регистрации это не записывается в нового менеджера. Итог: непонятно, к какому владельцу прикреплён менеджер. Поэтому [HomeServiceImpl.resolveOwner](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L248-L252) для менеджера возвращает его самого, и менеджер **видит пустой список залов**. Весь UX менеджера сломан.

### P1 — Должно быть исправлено в ближайшее время

6. **Несоответствие префикса путей** — docs говорят `/api/v1/auth/**`; [AuthController](../../src/main/java/kg/sportmanager/controller/AuthController.java#L25) использует `/auth/**`. Мобильный клиент, реализующий по docs, получит 404.

7. **Два разных формата ошибок** — `AuthServiceImpl` бросает `ResponseStatusException(status, "ERROR_CODE")` → дефолтное тело Spring (`{"status":401,"error":"Unauthorized","message":"INVALID_CREDENTIALS"}`). Остальные сервисы используют `AppException` + GlobalExceptionHandler с мультиязычным `{code, message:{en,ru,ky}}`. Клиент, ожидающий единый формат, не сможет распарсить auth-ошибки.

8. **Карта `MESSAGES` неполная** — [GlobalExceptionHandler](../../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L13-L54) содержит только 8 кодов. Отсутствуют: `SESSION_NOT_FOUND`, `SESSION_NOT_ACTIVE`, `SESSION_NOT_PAUSED`, `SESSION_ALREADY_COMPLETED`, `CANCEL_WINDOW_EXPIRED`, `INVALID_DISCOUNT`, `MANAGER_NOT_FOUND`, `NOT_ENOUGH_DATA`, `INVALID_INVITE_CODE`, `INVALID_CREDENTIALS`, `EMAIL_ALREADY_USED`, `PHONE_ALREADY_USED`, `ACCOUNT_LOCKED`, `BAD_REQUEST`, `SUBSCRIPTION_REQUIRED`, `PAYMENT_NOT_FOUND` и т.д. Когда кода нет в карте, fallback подставляет сам код в текст сообщения → пользователь видит «SESSION_NOT_FOUND».

9. **Race condition в start сессии** — [SessionServiceImpl.start](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L51-L69): `existsByTableAndIsActiveTrue` → insert. Pessimistic lock'а нет. Два параллельных start могут создать две ACTIVE-сессии на одном столе. Docs (`session_api.md`): «Backend блокирует стол в транзакции».

10. **Пароль пишется в лог** — [AuthController.login:37](../../src/main/java/kg/sportmanager/controller/AuthController.java#L37) `log.info("Login request: {}", request)` → `LoginRequest.toString()` (Lombok @Data) пишет пароль в открытом виде. GDPR + внутренняя безопасность.

---

## Сквозные проблемы (затрагивают все домены)

### Расхождения Doc–Code

| Тема                                           | Doc                             | Код                                           | Эффект                                |
| ---------------------------------------------- | ------------------------------- | --------------------------------------------- | ------------------------------------- |
| Auth path prefix                               | `/api/v1/auth/**`               | `/auth/**`                                    | Мобила получает 404                   |
| Refresh response                               | Только токены                   | `{user, accessToken, refreshToken}`           | Нарушение docs, не критично           |
| `details` в теле ошибки                        | `null` или массив               | Отсутствует вообще (нет поля в ErrorResponse) | Validation-ошибки не парсятся         |
| Validation `details: [{field, rule, message}]` | Есть                            | Нет — всегда `VALIDATION_ERROR`               | Мобила не знает, какое поле невалидно |
| Поведение `Accept-Language`                    | Если заголовок есть — один язык | Всегда 3 языка                                | В docs опционально, не критично       |
| Currency `KGS\|USD\|RUB\|KZT\|TRY`             | 5 значений                      | Те же 5 в enum                                | OK                                    |
| `tarifAmountSnapshot` integer                  | integer                         | `Integer`                                     | OK                                    |
| Session `managerId`                            | uuid                            | `UUID` (есть в сущности)                      | OK, но в DTO ответа поля нет (см. 03) |

### Отсутствие валидации

Ни в одном DTO нет Jakarta validation-аннотаций (`@NotBlank`, `@Size`, `@Min`, `@Email`, `@Pattern`). В контроллерах нет `@Valid`. Вся валидация написана руками в сервисах и **не может отдавать массив `details`, описанный в docs.** Два варианта:

- A) Jakarta Validation + `@RestControllerAdvice`-обработчик `MethodArgumentNotValidException`, заполняющий `details`.
- B) Расширить нынешнюю ручную валидацию: `AppException` должен нести список `details`.

### N+1 запросы

- [HomeServiceImpl.getVenueList](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L37-L45): на каждый зал — `findByVenueAndDeletedAtIsNullOrderByNumberAsc` (грузит все строки только ради count). 10 залов × 50 столов = 500 строк впустую. **Добавить `countByVenueAndDeletedAtIsNull`.**
- [ReportsServiceImpl.getTables](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L146-L165): на каждый стол — 2 запроса (revenue + count). N столов = 2N запросов. Заменить одним `GROUP BY`, как в `getOverview`.
- [HomeServiceImpl.buildSelectedVenueResponse](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L227-L238): на каждый стол — `findByTableAndIsActiveTrue`. Решается одним `findActiveByTableIn(...)`.

### Отсутствующие сквозные возможности

- **Нет CORS-конфига** → preflight с другого origin вернёт 401
- **Нет rate limiting** → docs указывают лимиты в минуту, но не реализовано
- **Нет audit log** — кто и когда стартовал/отменил сессию в БД есть, но не отображается «для владельца»
- **Нет тестов** — test-scope зависимости в pom.xml есть, но `src/test/` пуст
- **Нет health endpoint** — Spring Boot Actuator не подключён
- **Нет кэша** — docs предлагают server-side cache 5 мин для reports; не реализовано

---

## Заметки по качеству кода

- **Языки комментариев перемешаны** — русский + турецкий + английский. Нужно определиться (раз описание проекта на русском — логично русский, но docs на турецком).
- **В DTO непоследовательные типы дат** — где-то `String createdAt` (через `.toString()`), где-то `Instant` (Jackson serialization). [HomeMapper](../../src/main/java/kg/sportmanager/mapper/HomeMapper.java) использует `toString()` → формат без миллисекунд `"2026-04-15T10:30:00Z"`. Примеры в docs заканчиваются на `.000Z` → отдать сериализацию Jackson через `Instant`.
- **`CommonMapper.java` пустой** — `@UtilityClass` без методов. Удалить.
- **Двойственность `SessionResponse` vs `SessionLiteResponse`** — HomeMapper создаёт `SessionResponse` (со строковыми датами), SessionMapper создаёт `SessionLiteResponse` (с Instant). Две разные DTO описывают одну модель. Объединить.
- **`CommonMapper` и `SessionMapper` лежат в `util/`, а `HomeMapper` в `mapper/`** — непоследовательное расположение.
- **Нет `@JsonIgnore`** — поля password и refreshToken у `User` могут утечь; `@AuthenticationPrincipal User` напрямую из контроллеров не возвращается, но для защиты от ошибок добавить стоит.
- **Имя сущности `Tables` во множественном числе** — JPA-naming тут лишний. `Table` — не ключевое слово в Java, но конфликтует с `jakarta.persistence.Table`. Было бы понятнее `RoomTable`/`PoolTable`.

---

## Отдельные файлы ревью

| Файл                                                                         | Область                                                        |
| ---------------------------------------------------------------------------- | -------------------------------------------------------------- |
| [01-auth-review.md](01-auth-review.md)                                       | Эндпоинты `/auth/*`, JWT-поток, invite code                    |
| [02-home-review.md](02-home-review.md)                                       | CRUD по Venue + Table, разрыв manager→owner                    |
| [03-session-review.md](03-session-review.md)                                 | Жизненный цикл сессии, snapshot, расчёт, **дыры безопасности** |
| [04-reports-review.md](04-reports-review.md)                                 | 8 эндпоинтов reports, NPE, bucketing, forecast                 |
| [05-managers-review.md](05-managers-review.md)                               | (Не реализовано — описание пробелов + roadmap)                 |
| [06-subscription-review.md](06-subscription-review.md)                       | (Не реализовано — описание пробелов + roadmap)                 |
| [07-security-error-handling-review.md](07-security-error-handling-review.md) | Детали JWT, формат ошибок, i18n, EntryPoint, AccessDenied      |
| [08-config-build-review.md](08-config-build-review.md)                       | pom.xml, application.yml, Dockerfile, пробелы в build/test     |
