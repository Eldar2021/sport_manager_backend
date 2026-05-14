# Ревью Security, Error Handling & i18n

Код: [SecurityConfiguration](../../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java), [JwtAuthFilter](../../src/main/java/kg/sportmanager/security/JwtAuthFilter.java), [JwtUtil](../../src/main/java/kg/sportmanager/security/JwtUtil.java), [JwtAuthEntryPoint](../../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java), [JwtAccessDeniedHandler](../../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java), [AppException](../../src/main/java/kg/sportmanager/exception/AppException.java), [GlobalExceptionHandler](../../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java), [MessageConfiguration](../../src/main/java/kg/sportmanager/configuration/MessageConfiguration.java)

---

## P0 — Критично

### 1. Формат ошибок отдаётся в двух разных видах

**Формат A (AuthServiceImpl):** дефолтное тело Spring при `ResponseStatusException`:

```json
{
  "timestamp": "...",
  "status": 401,
  "error": "Unauthorized",
  "message": "INVALID_CREDENTIALS",
  "path": "/auth/login"
}
```

**Формат B (Home/Session/Reports + GlobalExceptionHandler):** мультиязычный:

```json
{
  "code": "VENUE_NOT_FOUND",
  "message": { "en": "...", "ru": "...", "ky": "..." }
}
```

**Формат C (JwtAuthEntryPoint / JwtAccessDeniedHandler):** мультиязычный, но с `"details": "null"` (строка `"null"`):

```json
{ "code": "UNAUTHORIZED", "message": { ... }, "details": "null" }
```

Три разных структуры — на клиенте нельзя написать один парсер. Исправление:

- `AuthServiceImpl` должен использовать `AppException`.
- Entry/AccessDeniedHandler должны отдавать DTO `ErrorResponse` (или сериализованный ObjectMapper'ом), `details: null` — литералом.
- `GlobalExceptionHandler` — единственный источник истины формата.

### 2. `details: "null"` — строка, не JSON-null

[JwtAuthEntryPoint:34](../../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java#L34):

```java
Map<String, Object> body = Map.of(
    "code", "UNAUTHORIZED",
    "message", Map.of(...),
    "details", "null"   // ← строка "null", сериализуется как литеральная строка "null"
);
```

Вывод:

```json
{ "details": "null" }
```

Docs:

```json
{ "details": null }
```

Проверка `if (err.details == null)` на клиенте даёт false → классический баг. Та же проблема в [JwtAccessDeniedHandler:34](../../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java#L34).

**Исправление:** `Map.of` не принимает null → использовать `HashMap` и `put("details", null)`:

```java
Map<String, Object> body = new HashMap<>();
body.put("code", "UNAUTHORIZED");
body.put("message", Map.of("en", ..., "ru", ..., "ky", ...));
body.put("details", null);
objectMapper.writeValueAsString(body);
```

Чище — отдавать DTO `ErrorResponse`, где `details` по умолчанию null. В текущем [`ErrorResponse`](../../src/main/java/kg/sportmanager/dto/response/ErrorResponse.java) поля `details` **вообще нет** — нужно добавить.

### 3. В карте `MESSAGES` нет нужных кодов — пользователь видит код в качестве сообщения

[GlobalExceptionHandler:58-60](../../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L58-L60):

```java
Map<String, String> msg = MESSAGES.getOrDefault(ex.getCode(),
    Map.of("en", ex.getCode(), "ru", ex.getCode(), "ky", ex.getCode()));
```

Для отсутствующих кодов пользователь видит `"SESSION_NOT_FOUND"` → некрасиво и нарушает контракт docs (гарантируется 3 языка для каждого кода). Отсутствуют:

- Session: `SESSION_NOT_FOUND`, `SESSION_NOT_ACTIVE`, `SESSION_NOT_PAUSED`, `SESSION_ALREADY_COMPLETED`, `CANCEL_WINDOW_EXPIRED`, `INVALID_DISCOUNT`, `SESSION_ALREADY_CANCELLED` (предлагаемый)
- Reports: `REPORT_NOT_FOUND`, `MANAGER_NOT_FOUND`, `NOT_ENOUGH_DATA`
- Auth: `INVALID_INVITE_CODE`, `INVALID_CREDENTIALS`, `EMAIL_ALREADY_USED`, `PHONE_ALREADY_USED`, `ACCOUNT_LOCKED`, `SESSION_EXPIRED`, `UNAUTHORIZED`, `BAD_REQUEST`
- Subscription: `SUBSCRIPTION_REQUIRED`, `NO_TABLES`, `INVALID_DURATION`, `PAYMENT_NOT_FOUND`, `PAYMENT_ALREADY_PROCESSED`, `PAYMENT_PROVIDER_ERROR`, `PRICING_MISMATCH`
- Manager: `HAS_ACTIVE_SESSION`

Все нужно добавить с переводами en/ru/ky. Поскольку текста много, разумно использовать `messages_*.properties`:

### 4. `messages*.properties` пусты, MessageSource не используется

[`messages.properties`](../../src/main/resources/messages.properties), [`messages_ru.properties`](../../src/main/resources/messages_ru.properties), [`messages_ky.properties`](../../src/main/resources/messages_ky.properties) — все пустые.

[MessageConfiguration](../../src/main/java/kg/sportmanager/configuration/MessageConfiguration.java) объявляет bean `MessageSource`, но никто его не `@Autowired`. CLAUDE.md прямо отмечает:

> Файлы `messages_*.properties` существуют, но handler их не читает — переводы зашиты в коде.

**Нужно решение:** либо заполнить properties и переписать GlobalExceptionHandler так, чтобы он брал переводы из `MessageSource`, либо удалить `MessageConfiguration` и properties-файлы. Гибрид (часть в коде, часть в файлах) — лишняя поддержка.

Моё предложение: заполнить properties и читать через MessageSource:

```java
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private final MessageSource messages;

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handle(AppException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.of(
            ex.getCode(),
            messages.getMessage(ex.getCode(), null, Locale.ENGLISH),
            messages.getMessage(ex.getCode(), null, Locale.forLanguageTag("ru")),
            messages.getMessage(ex.getCode(), null, Locale.forLanguageTag("ky"))
        ));
    }
}
```

`messages_*.properties`:

```properties
SESSION_NOT_FOUND=Session not found
VENUE_NOT_FOUND=Selected venue not found
```

Правило «всегда 3 языка» из docs сохранится. При необходимости single-language через `Accept-Language` — добавить позже.

### 5. Поведение `Accept-Language` отличается от docs

Docs:

> Если `Accept-Language` задан, error-сообщения могут возвращаться только на этом языке (чтобы уменьшить размер ответа).

Код всегда возвращает 3 языка — это поведение по умолчанию из docs («если клиент не прислал»). Не критично — UI справится. **Но** опционально можно подключить через `LocaleContextHolder.getLocale()` (single-language switch).

---

## P1 — Высокий

### 6. JWT secret в репозитории, не в `.gitignore`

[application.yml:23](../../src/main/resources/application.yml#L23):

```yaml
jwt:
  secret: "8fK29xLmQwPz91AaBcDeFgHiJkLmNoPqRsTuVwXyZ123456"
```

Закоммичен. Любая утечка → **все прод-токены придётся инвалидировать**. Исправление:

```yaml
jwt:
  secret: "${JWT_SECRET}"
  access-expiration: "${JWT_ACCESS_EXPIRATION:900000}"
  refresh-expiration: "${JWT_REFRESH_EXPIRATION:2592000000}"
```

Без default — прод не стартует без env var (fail-fast). Для локального dev — `application-dev.yml` или `.env` в `.gitignore`.

### 7. JWT secret использует platform default charset

[JwtUtil.getKey:24-26](../../src/main/java/kg/sportmanager/security/JwtUtil.java#L24-L26):

```java
return Keys.hmacShaKeyFor(secret.getBytes());
```

`secret.getBytes()` — кодировка по умолчанию платформы. На Linux обычно UTF-8, на Windows — CP1252. При кросс-платформенном деплое токены могут стать несовместимыми.

Исправление:

```java
return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
```

### 8. В JWT нет issuer / audience

В токене только `subject` (userId) и claim `role`. Нет `iss` (issuer) и `aud` (audience). Если несколько сервисов используют один secret — возможен cross-service replay. Для одного сервиса не критично, но:

```java
.setIssuer("sport-manager")
.setAudience("mobile")
```

— добавить и проверять при валидации.

### 9. Нет обнаружения «использованного» refresh-токена (burned token detection)

См. [01-auth-review.md](01-auth-review.md) #10. Если использовать старый refresh повторно — система не считает токен «украденным». Вне MVP, но запомнить.

### 10. Нет blacklist для access-токена при логауте

Logout только обнуляет `user.refreshToken`. Access-токен (15 минут) остаётся валидным. Если access-токен украли — после logout-а его ещё 15 минут можно использовать.

Docs принимают это:

> Клиент сначала чистит локальный токен, потом дёргает remote; ошибки backend проглатываются.

Для MVP терпимо. В проде: blacklist (Redis) или короткий expiry (5 мин).

### 11. JwtAuthFilter не различает expired и invalid token

[JwtUtil.isTokenValid:52-59](../../src/main/java/kg/sportmanager/security/JwtUtil.java#L52-L59):

```java
public boolean isTokenValid(String token) {
    try {
        Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token);
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

`ExpiredJwtException`, `SignatureException`, `MalformedJwtException` — все возвращают false. Клиент не может отличить «истёк → дёргать refresh» от «невалиден → logout». EntryPoint всегда отдаёт одно и то же сообщение.

Исправление:

```java
public TokenStatus validate(String token) {
    try {
        Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token);
        return TokenStatus.VALID;
    } catch (ExpiredJwtException e) { return TokenStatus.EXPIRED; }
    catch (Exception e) { return TokenStatus.INVALID; }
}
```

EntryPoint отдаёт разные `code` (`TOKEN_EXPIRED` vs `INVALID_TOKEN`). Для refresh-флоу мобилы это критично (`SESSION_EXPIRED` триггерит refresh).

### 12. Исключение в `extractUserId` ломает весь фильтр

[JwtAuthFilter:39-48](../../src/main/java/kg/sportmanager/security/JwtAuthFilter.java#L39-L48):

```java
if (jwtUtil.isTokenValid(token)) {
    String userId = jwtUtil.extractUserId(token);
    userRepository.findById(UUID.fromString(userId)).ifPresent(user -> { ... });
}
```

`extractUserId` повторно парсит валидный токен — потеря производительности. Парсинг идёт дважды. Плюс: если subject в claims не UUID — `UUID.fromString` бросает, и filter ловит 500. try-catch нет.

Исправление: один parse + читать поля из Claims.

### 13. JwtUtil не различает access и refresh по claim

Access и refresh подписываются одним ключом, отличается только expiry. Refresh-токен можно прислать в Authorization-заголовке к `/api/v1/...` — фильтр не отличит. На практике редко, но контракт нарушается.

Исправление: добавить в refresh `"type": "refresh"`, фильтр пускает только `type=access`.

### 14. В SecurityConfig нет CORS

В `SecurityFilterChain` нет `.cors(...)`. Мобила или web-preview с другого origin получит preflight 401.

Исправление:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.sportmanager.kg", "http://localhost:*"));
    config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE"));
    config.setAllowedHeaders(List.of("*"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

И `.cors(Customizer.withDefaults())` в chain.

### 15. CSRF выключен — OK, но без комментария

JWT-only API не нуждается в CSRF. `.csrf(disable)` корректно, но комментарий не помешает.

### 16. Stateless session — OK

`SessionCreationPolicy.STATELESS` — для JWT правильно, sticky session не нужны.

### 17. BCrypt — OK

Default 10 раундов. В проде безопаснее 12+, но 10 приемлем.

### 18. `log.info("jwtAuthFilter")` пишется на каждый запрос

[JwtAuthFilter:31](../../src/main/java/kg/sportmanager/security/JwtAuthFilter.java#L31):

```java
log.info("jwtAuthFilter");
```

Спам в проде. Понизить до debug или удалить.

### 19. Запрос на login пишется в лог с паролем

[AuthController:37](../../src/main/java/kg/sportmanager/controller/AuthController.java#L37) — пароль попадает в лог. См. [01-auth-review.md](01-auth-review.md) #2.

### 20. Слишком много public-путей под Swagger

[SecurityConfiguration:42-52](../../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java#L42-L52) — `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/webjars/**` все open. В проде стоит отключать Swagger через config-флаг.

---

## P2 — Средний

### 21. Нет валидации Origin/Referer

CSRF выключен + JWT → API «any-origin». Для мобилы OK, для web-bridge риск. Жёсткая CORS-конфигурация снижает.

### 22. Нет rate limiting на чувствительных эндпоинтах

Login без защиты от brute force. Bucket4j или Spring Cloud Gateway с лимитом в минуту.

### 23. На `User.password` и `User.refreshToken` нет `@JsonIgnore`

Сущность напрямую не отдаётся, но для защиты:

```java
@JsonIgnore
private String password;

@JsonIgnore
private String refreshToken;
```

### 24. SQL injection

JPQL с параметрами → риска нет. Конкатенаций нигде не видно. ✅

### 25. XSS

Backend отдаёт JSON, прямого XSS нет. Но в `cancelReason` и `description` можно положить `<script>`; если фронт вставит в DOM без эскейпа — риск. Бэк не должен очищать (это контент), фронт должен экранировать.

### 26. Mass assignment

DTO отделены от сущностей → mass assignment невозможен. ✅

### 27. Risk email enumeration

`POST /auth/forgot-password` docs:

> Backend ради приватности отдаёт 200 независимо от того, есть email или нет.

Код ничего не делает, отдаёт 200 ✓. Но при реальной реализации нельзя забывать — отдавать `EMAIL_NOT_FOUND` — вектор enumeration.

`POST /auth/register` отдаёт `409 EMAIL_ALREADY_USED` → enumeration возможна. Docs это принимают. Терпимо, но иметь в виду.

### 28. У reset-токена пароля нет TTL (когда реализуем)

При реализации `forgot-password`: ссылка должна жить 15-30 мин и быть одноразовой.

### 29. Нет `@PreAuthorize`

Все проверки роли — руками (`requireOwner`). Встроенный `@PreAuthorize("hasRole('OWNER')")` чище, но и текущий подход допустим — CLAUDE.md держит принцип «role checks in service».

---

## Рекомендации

1. **Свести формат ошибок к одному** — `AuthServiceImpl` на `AppException`, EntryPoint/AccessDeniedHandler отдают `ErrorResponse`.
2. **`details: null` как JSON-литерал** — через `HashMap` или DTO `ErrorResponse` с полем `details`.
3. **Заполнить карту MESSAGES** — 20+ кодов с en/ru/ky.
4. **Использовать properties или удалить их** — определиться.
5. **JWT secret из env var** — fail-fast в проде.
6. **Явно UTF-8** — `secret.getBytes(StandardCharsets.UTF_8)`.
7. **Различать expired и invalid** — критично для refresh-флоу.
8. **`@JsonIgnore`** на User.
9. **Настроить CORS**.
10. **Убрать `log.info("jwtAuthFilter")` и логирование пароля**.
11. **Type-claim для access** — чтобы refresh не пропускали в Authorization.
