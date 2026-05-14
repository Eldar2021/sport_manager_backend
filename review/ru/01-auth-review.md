# Ревью Auth

Источник: [docs/auth-api.md](../../docs/auth-api.md)  
Код: [AuthController](../../src/main/java/kg/sportmanager/controller/AuthController.java), [AuthServiceImpl](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java), [User](../../src/main/java/kg/sportmanager/entity/User.java), [InviteCode](../../src/main/java/kg/sportmanager/entity/InviteCode.java), [JwtUtil](../../src/main/java/kg/sportmanager/security/JwtUtil.java), [SecurityConfiguration](../../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java)

---

## Матрица соответствия эндпоинтов

| Путь в docs                         | Поведение в docs                                        | Путь в коде                                         | Соответствие                                       |
| ----------------------------------- | ------------------------------------------------------- | --------------------------------------------------- | -------------------------------------------------- |
| `POST /api/v1/auth/login`           | 200 + `{user, accessToken, refreshToken}`               | `POST /auth/login`                                  | ⚠️ **Путь отличается**, body совпадает             |
| `POST /api/v1/auth/register`        | 200 + auth body, OWNER/MANAGER discriminator + invite   | `POST /auth/register`                               | ⚠️ Путь отличается, body совпадает                 |
| `POST /api/v1/auth/refresh`         | 200, **только** `{accessToken, refreshToken}`           | `AuthResponse` содержит user                        | ❌ Нарушение docs                                  |
| `POST /api/v1/auth/logout`          | 200/204, пустое body                                    | `POST /auth/logout`                                 | ✅ Путь отличается, поведение совпадает            |
| `POST /api/v1/auth/forgot-password` | Сгенерировать пароль + отправить email + 200            | `POST /auth/forgot-password` — **ничего не делает** | ❌ Заглушка                                        |
| `POST /api/v1/auth/invite-code`     | OWNER-only, `{code, expiresAt}` + **subscription gate** | `POST /auth/invite-code`                            | ⚠️ Нет subscription gate, role-check в контроллере |

---

## P0 — Критично

### 1. `forgot-password` — заглушка

[AuthController:70-72](../../src/main/java/kg/sportmanager/controller/AuthController.java#L70-L72):

```java
public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
    return ResponseEntity.ok().build();
}
```

Ничего не делает. Docs:

> Генерируется новый пароль, отправляется на email пользователя, пользователь входит, затем меняет пароль.

Итог: в проде кнопка «забыл пароль» молча проваливается. Либо возвращать `SERVICE_UNAVAILABLE`, либо реализовать настоящий email-флоу (Spring Mail + JavaMailSender + временный пароль). Минимум: проверять зарегистрирован ли email и детерминированно отвечать 422/404.

### 2. Пароль пишется в лог

[AuthController:37](../../src/main/java/kg/sportmanager/controller/AuthController.java#L37):

```java
log.info("Login request: {}", request);
```

`LoginRequest` с `@Data` имеет `toString()`, который раскрывает `username` и `password`:

```
2026-05-14 ... Login request: LoginRequest(username=test@x.com, password=Test1234)
```

**Срочно:** добавить `@ToString.Exclude` на `password` или не логировать `LoginRequest` целиком. Та же проблема в register (`RegisterRequest.password` и `inviteCode`).

### 3. Несоответствие префикса путей

Docs указывают `/api/v1/auth/**` для всех auth-эндпоинтов. Код использует `/auth/**`. Мобильная команда, реализующая по docs, получит 404. Два варианта:

- A) Перевести контроллер на `/api/v1/auth` (правильный путь)
- B) Обновить docs до `/auth/**`, [SecurityConfiguration permitAll-список](../../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java#L43-L46) уже корректен

Рекомендация: **A**. Единый префикс для всех эндпоинтов.

---

## P1 — Высокий

### 4. Refresh response содержит user

[AuthServiceImpl.buildAuthResponse:120-137](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L120-L137) всегда заполняет `user`. Docs (раздел refresh):

> 200 OK — response body  
> **Возвращаются только токены; поля user нет.**

Исправление: отдельный DTO `TokenPairResponse` для refresh, либо `buildAuthResponse(user, boolean includeUser)`.

### 5. Формат ошибок не мультиязычный

`ResponseStatusException(HttpStatus.X, "ERROR_CODE")` отдаёт дефолтное тело Spring:

```json
{
  "timestamp": "...",
  "status": 401,
  "error": "Unauthorized",
  "message": "INVALID_CREDENTIALS",
  "path": "/auth/login"
}
```

Docs и остальные сервисы (Home/Session/Reports) отдают `{code, message: {en, ru, ky}}`. Итог: единый парсер ошибок на клиенте не написать. Исправление:

```java
// В AuthServiceImpl:
throw new AppException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
```

Затем в [GlobalExceptionHandler.MESSAGES](../../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L13) добавить:

- `INVALID_CREDENTIALS`
- `ACCOUNT_LOCKED`
- `INVALID_INVITE_CODE`
- `EMAIL_ALREADY_USED`
- `PHONE_ALREADY_USED`
- `SESSION_EXPIRED` (для refresh)
- `UNAUTHORIZED` (общий)

### 6. Role-check для invite-code в контроллере

[AuthController.inviteCode:79-84](../../src/main/java/kg/sportmanager/controller/AuthController.java#L79-L84):

```java
if (user.getRole() != User.Role.OWNER) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
}
```

Явное правило в [CLAUDE.md](../../CLAUDE.md):

> Проверки роли (OWNER vs MANAGER) **не** делаются на уровне URL-паттернов — они в методах сервисов.

Плюс `ResponseStatusException(403)` не отдаёт мультиязычное тело. Исправление: в начале `AuthServiceImpl.generateInviteCode` — `if (user.getRole() != OWNER) throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);`.

### 7. Нет subscription gate для invite-code

Docs ([auth-api.md §6](../../docs/auth-api.md#6-post-apiv1authinvite-code)):

> 403 `SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` или `GRACE@0`

Subscription-модуля нет вообще → правило игнорируется. (См. [06-subscription-review.md](06-subscription-review.md).)

### 8. Связь Manager↔Owner теряется при регистрации

[AuthServiceImpl.register:73-81](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L73-L81):

```java
User user = User.builder()
    .name(...)
    .role(request.getRole())
    .build();
userRepository.save(user);
```

При создании менеджера `invite.getOwner()` известен (хранится в `InviteCode`), но **не записывается** в новую запись User. У `User` вообще нет поля `owner`. Итог:

- Непонятно, к какому владельцу прикреплён менеджер
- HomeService отдаёт менеджеру неправильный список залов (свои = пустые)
- В Reports менеджера нельзя отфильтровать

**Комплексное исправление:**

1. Добавить в `User` поле `@ManyToOne private User owner;` (nullable, null для OWNER).
2. В `AuthServiceImpl.register` для MANAGER — `user.setOwner(invite.getOwner())`.
3. Использовать это поле в `HomeServiceImpl.resolveOwner` и `SessionServiceImpl.validateTableAccess`.

### 9. JWT secret: контроль минимального размера и charset

[JwtUtil.getKey():24-26](../../src/main/java/kg/sportmanager/security/JwtUtil.java#L24-L26):

```java
return Keys.hmacShaKeyFor(secret.getBytes());
```

Используется platform default charset. Чувствительный к кодировке secret (Latin1 vs UTF-8) даст разные подписи на разных машинах. **Указывать `StandardCharsets.UTF_8`.**

Дополнительно: для HS512 нужен secret >= 64 байт. В application.yml — 46 символов. `signWith(getKey())` в jjwt 0.11.5 выбирает алгоритм автоматически (для 32 байт — HS256). 46 байт хватит для HS256 — OK, но явно указать `SignatureAlgorithm.HS256` для чёткости контракта.

### 10. Слабое сравнение «сохранённый vs присланный» в refresh

[AuthServiceImpl.refresh:85-95](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L85-L95):

```java
User user = userRepository.findByRefreshToken(request.getRefreshToken())
        .orElseThrow(() -> new ResponseStatusException(401, "SESSION_EXPIRED"));
if (!jwtUtil.isTokenValid(request.getRefreshToken())) {
    throw new ResponseStatusException(401, "SESSION_EXPIRED");
}
return buildAuthResponse(user);
```

Хорошо: пользователь ищется по `findByRefreshToken` — то есть refresh из body должен совпадать с тем, что в БД. **Но:** возможен сценарий двойного использования:

1. Устройство A зовёт `/refresh` → создаётся новый `R2`, `user.refreshToken = R2`.
2. Устройство A не получает ответ (сеть), повторяет со старым `R1` → в БД его уже нет → 401, цикл прерывается. OK.

Также edge case: race condition. Два параллельных `/refresh` с одним `R1` — один найдёт `R1` и запишет `R2`, второй уже не найдёт `R1` → один упадёт. Терпимо.

**Главное:** ротация refresh-токенов в плане безопасности требует «burned token detection». Если приходит старый refresh — токен мог быть украден → инвалидировать все сессии. Вне MVP, но стоит запомнить.

---

## P2 — Средний

### 11. Нет валидации формата email/phone

В `RegisterRequest` нет `@Email`, `@Pattern`. Передадите «abc» в email — пройдёт, сохранится, потом login не сработает. Docs говорят `phone: E.164-ish (+996 ...)`.

### 12. Нет проверки сложности пароля

`password: "1"` принимается. Нет минимальной длины, нет требований к составу. Минимум `@Size(min=8)`.

### 13. Account locked нельзя установить вручную

Поле `User.locked` есть, но никто не выставляет его в `true`. Нет защиты от brute force. Docs упоминают `423 ACCOUNT_LOCKED`, но backend никогда не блокирует.

### 14. Нет защиты от спама invite-кодов

Владелец может бесконечно вызывать `POST /auth/invite-code` → БД растёт. Если есть неиспользованный непросроченный код — не создавать новый, либо ввести rate limit.

### 15. Нет проверки «is owner OWNER» внутри invite-code сервиса

[AuthServiceImpl.generateInviteCode:102](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L102) принимает `owner` параметром, но role-check — в контроллере. Если контроллер обойдут (другой сервис), уязвимость. Переезд проверки в сервис решает проблему (пункт #6).

### 16. Несогласованный формат `expiresAt` в invite-code

[AuthServiceImpl:114-116](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L114-L116):

```java
.expiresAt(expiresAt.toString())  // LocalDateTime.toString() → "2026-05-21T12:34:56.123"
```

Docs: `expiresAt: "2026-05-04T12:34:56Z"` (UTC, суффикс Z). `LocalDateTime` не несёт информацию о таймзоне → нет «Z». Если хотим UTC — использовать `Instant.now().plus(7, DAYS)` или `OffsetDateTime`, либо хранить `Instant` в DTO и дать Jackson его сериализовать.

### 17. `User.getUsername()` возвращает email

```java
@Override
public String getUsername() { return email; }
```

Для контракта UserDetails — OK. Но в [ReportsRepository.managerStats](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L156-L170) проекция `s.manager.email AS username` уходит в DTO. Docs для managers/reports описывают отдельное поле `username` (вида `aibek`). В сущности `User` поля `username` нет вообще. См. [04-reports-review.md](04-reports-review.md).

### 18. `AuthResponse.user.role` String, «OWNER»/«MANAGER» в UPPERCASE

OK — `role(user.getRole().name())` возвращает имя enum. Контракт docs соблюдается.

---

## P3 — Низкий / Качество кода

- Swagger-описания в `AuthController` на русском, остальной код — смесь английского/турецкого.
- `inviteCodeRepository.findByOwner(User owner)` определён, но нигде не используется. Dead code.
- Поле `User.locked` через Lombok `@Data` даёт `isLocked()`/`setLocked()`; `UserDetails.isAccountNonLocked()` возвращает `!locked` — OK.
- Нет `@JsonIgnore` на `User` — если контроллер случайно вернёт сущность, password/refreshToken утекут. Сейчас `User` напрямую не возвращается (есть UserResponse), но для защиты стоит добавить.
- Флаг `InviteCode.used` выставляется, но кроме `findByCodeAndUsedFalse` запросов на него нет. Просроченные коды остаются с `used=false` и никогда не чистятся. Cleanup-крон отсутствует (опционально).
- `inviteCode` тоже не должен попадать в логи (то же решение, что в #2).

---

## Рекомендации (без порядка)

1. **Унифицировать формат ошибок** — `AuthServiceImpl` на `AppException`, дополнить `MESSAGES`.
2. **`/auth/**`→`/api/v1/auth/**`** или обновить docs. Одно из двух.
3. **`@ToString.Exclude` или убрать логирование payload-ов целиком.**
4. **Реализовать `forgot-password` либо вернуть `SERVICE_UNAVAILABLE`.**
5. **Добавить `User.owner` и заполнять в register.**
6. **Убрать `user` из ответа `refresh`.**
7. **Перенести role-check в сервис** (`generateInviteCode`).
8. **Хук для subscription gate** — когда модуль появится, применить через [SubscriptionGuard / @PreAuthorize](06-subscription-review.md) к `generateInviteCode`.
9. **JWT secret из env var** — `${JWT_SECRET:default}`. Продакшен-secret не должен попадать в коммиты.
10. **Валидация email + phone, сложность пароля, lockout-on-fail** — минимум.
