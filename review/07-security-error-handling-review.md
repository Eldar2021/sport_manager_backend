# Security, Error Handling & i18n Review

Kod: [SecurityConfiguration](../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java), [JwtAuthFilter](../src/main/java/kg/sportmanager/security/JwtAuthFilter.java), [JwtUtil](../src/main/java/kg/sportmanager/security/JwtUtil.java), [JwtAuthEntryPoint](../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java), [JwtAccessDeniedHandler](../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java), [AppException](../src/main/java/kg/sportmanager/exception/AppException.java), [GlobalExceptionHandler](../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java), [MessageConfiguration](../src/main/java/kg/sportmanager/configuration/MessageConfiguration.java)

---

## P0 — Kritik

### 1. Hata zarfı iki ayrı formatta dönüyor

**Format A (AuthServiceImpl):** Spring default `ResponseStatusException` body:
```json
{ "timestamp": "...", "status": 401, "error": "Unauthorized", "message": "INVALID_CREDENTIALS", "path": "/auth/login" }
```

**Format B (Home/Session/Reports + GlobalExceptionHandler):** multilingual:
```json
{ "code": "VENUE_NOT_FOUND", "message": { "en": "...", "ru": "...", "ky": "..." } }
```

**Format C (JwtAuthEntryPoint / JwtAccessDeniedHandler):** multilingual ama `"details": "null"` (string `"null"`):
```json
{ "code": "UNAUTHORIZED", "message": { ... }, "details": "null" }
```

3 farklı yapı — istemcide tek error parser yazılamaz. Düzeltme:

- AuthServiceImpl `AppException` kullansın.
- Entry/AccessDeniedHandler `ErrorResponse` DTO'sunu (veya direkt aynı ObjectMapper output'unu) kullansın, `details: null` literal yapsın.
- `GlobalExceptionHandler` formatı tek doğruluk kaynağı olsun.

### 2. `details: "null"` (string) — JSON literal null değil

[JwtAuthEntryPoint:34](../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java#L34):

```java
Map<String, Object> body = Map.of(
    "code", "UNAUTHORIZED",
    "message", Map.of(...),
    "details", "null"   // ← string "null", JSON serileştirme literal "null" stringi olarak çıkarır
);
```

Output:
```json
{ "details": "null" }
```

Doc:
```json
{ "details": null }
```

İstemcide `if (err.details == null)` kontrolü `"null"` string'iyle false döner — eski bug üreten kalıp. Aynı bug [JwtAccessDeniedHandler:34](../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java#L34)'te.

**Düzeltme:** `Map.of` null değer kabul etmez → `HashMap` kullan ve `put("details", null)`:

```java
Map<String, Object> body = new HashMap<>();
body.put("code", "UNAUTHORIZED");
body.put("message", Map.of("en", ..., "ru", ..., "ky", ...));
body.put("details", null);
objectMapper.writeValueAsString(body);
```

Daha temizi: `ErrorResponse` DTO'sunu döndür ve `details` field'ı default null. Mevcut [`ErrorResponse`](../src/main/java/kg/sportmanager/dto/response/ErrorResponse.java)'da `details` field'ı **hiç yok** — eklenmesi gerek.

### 3. `MESSAGES` map'i eksik kodlar fallback'ten zarar görüyor

[GlobalExceptionHandler:58-60](../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L58-L60):

```java
Map<String, String> msg = MESSAGES.getOrDefault(ex.getCode(),
    Map.of("en", ex.getCode(), "ru", ex.getCode(), "ky", ex.getCode()));
```

Eksik kodlar için kullanıcı `"SESSION_NOT_FOUND"` mesajını görür → çirkin ve doc kontratı ihlali (doc her kod için 3 dil garanti ediyor). Eksik kodlar:

- Session: `SESSION_NOT_FOUND`, `SESSION_NOT_ACTIVE`, `SESSION_NOT_PAUSED`, `SESSION_ALREADY_COMPLETED`, `CANCEL_WINDOW_EXPIRED`, `INVALID_DISCOUNT`, `SESSION_ALREADY_CANCELLED` (önerilen)
- Reports: `REPORT_NOT_FOUND`, `MANAGER_NOT_FOUND`, `NOT_ENOUGH_DATA`
- Auth: `INVALID_INVITE_CODE`, `INVALID_CREDENTIALS`, `EMAIL_ALREADY_USED`, `PHONE_ALREADY_USED`, `ACCOUNT_LOCKED`, `SESSION_EXPIRED`, `UNAUTHORIZED`, `BAD_REQUEST`
- Subscription: `SUBSCRIPTION_REQUIRED`, `NO_TABLES`, `INVALID_DURATION`, `PAYMENT_NOT_FOUND`, `PAYMENT_ALREADY_PROCESSED`, `PAYMENT_PROVIDER_ERROR`, `PRICING_MISMATCH`
- Manager: `HAS_ACTIVE_SESSION`

Hepsi en/ru/ky ile MESSAGES'a eklenmeli. Çeviri uzun olacağı için `messages_*.properties` dosyaları kullanılabilir:

### 4. `messages*.properties` boş, MessageSource bean'i kullanılmıyor

[`messages.properties`](../src/main/resources/messages.properties), [`messages_ru.properties`](../src/main/resources/messages_ru.properties), [`messages_ky.properties`](../src/main/resources/messages_ky.properties) hepsi boş.

[MessageConfiguration](../src/main/java/kg/sportmanager/configuration/MessageConfiguration.java) `MessageSource` bean'i bind ediyor ama hiçbir yerden `@Autowired` edilmiyor. CLAUDE.md açıkça not düşmüş:

> `messages_*.properties` files exist but the handler does not currently read from them — translations live in code.

**Karar gerekiyor:** ya properties'leri doldur ve GlobalExceptionHandler'ı `MessageSource`'tan çevirileri çekecek şekilde refactor et, ya da `MessageConfiguration` ve properties dosyalarını sil. Hibrit (kodda + properties) bakım yükü.

Önerim: properties'i doldur, GlobalExceptionHandler `MessageSource`'tan oku:

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

Doc kuralı (her zaman 3 dil) korunur. `Accept-Language` ile tek dil dönmek istenirse logic eklenir.

### 5. `Accept-Language` davranışı doc'tan farklı

Doc:
> `Accept-Language` set edilmişse error mesajları sadece o dilde dönebilir (response boyutunu küçültmek için).

Kod her zaman 3 dili döner — bu da doc'a göre default ("client iletmediyse"). Önemli değil — UI esnek. **Ama** doc'taki opsiyonu istersek `LocaleContextHolder.getLocale()` ile single-language switch eklenebilir.

---

## P1 — Yüksek

### 6. JWT secret hardcoded, `.gitignore`'da değil

[application.yml:23](../src/main/resources/application.yml#L23):

```yaml
jwt:
  secret: "8fK29xLmQwPz91AaBcDeFgHiJkLmNoPqRsTuVwXyZ123456"
```

Repo'ya commit edilmiş. Bir kez leak olunca **tüm production token'lar invalidate edilmeli**. Düzeltme:

```yaml
jwt:
  secret: "${JWT_SECRET}"
  access-expiration: "${JWT_ACCESS_EXPIRATION:900000}"
  refresh-expiration: "${JWT_REFRESH_EXPIRATION:2592000000}"
```

Default değer yok → production'da env var olmadan başlatılamaz, fail-fast. Local dev için `application-dev.yml` veya `.env` dosyası `.gitignore`'da.

### 7. JWT secret platform default charset

[JwtUtil.getKey:24-26](../src/main/java/kg/sportmanager/security/JwtUtil.java#L24-L26):

```java
return Keys.hmacShaKeyFor(secret.getBytes());
```

`secret.getBytes()` platform default charset. Linux genelde UTF-8, Windows CP1252. Cross-platform deployment'ta token'lar uyumsuz olabilir.

Düzeltme:
```java
return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
```

### 8. JWT'de issuer / audience yok

Token'da sadece `subject` (userId) ve `role` claim'i. `iss` (issuer) ve `aud` (audience) yok. Birden fazla servis aynı secret'ı kullanıyorsa cross-service token replay mümkün. Tek servis için kritik değil ama:

```java
.setIssuer("sport-manager")
.setAudience("mobile")
```

ekle, doğrularken kontrol et.

### 9. Refresh token rotation "burned token" detection yok

Bkz. [01-auth-review.md](01-auth-review.md) #10. Eski bir refresh token tekrar kullanılırsa "çalınmış" olarak işaretlenmiyor. MVP scope dışı ama not.

### 10. Logout sırasında access token blacklist yok

Logout sadece `user.refreshToken = null`. Access token (15 dk geçerli) hala valid. Çalınan access token logout'tan sonra 15 dk daha kullanılabilir.

Doc bunu kabul ediyor:
> İstemci local token'ı önce temizler, sonra remote'a istek atar; backend hatası swallow edilir.

MVP için tolere edilir. Production: access token blacklist (Redis) veya kısa expiry (5dk).

### 11. JwtAuthFilter expired vs invalid token ayırt etmiyor

[JwtUtil.isTokenValid:52-59](../src/main/java/kg/sportmanager/security/JwtUtil.java#L52-L59):

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

`ExpiredJwtException`, `SignatureException`, `MalformedJwtException` — hepsi false döner. İstemci "token expired → refresh çağır" veya "invalid → logout" ayrımı yapamaz. EntryPoint hep aynı message döner.

Düzeltme:
```java
public TokenStatus validate(String token) {
    try {
        Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token);
        return TokenStatus.VALID;
    } catch (ExpiredJwtException e) { return TokenStatus.EXPIRED; }
    catch (Exception e) { return TokenStatus.INVALID; }
}
```

EntryPoint farklı `code` döner (`TOKEN_EXPIRED` vs `INVALID_TOKEN`). Mobil refresh flow için kritik (`SESSION_EXPIRED` döndüğünde refresh denenir).

### 12. `JwtAuthFilter` `extractUserId` exception'ı tüm filter'ı kırar

[JwtAuthFilter:39-48](../src/main/java/kg/sportmanager/security/JwtAuthFilter.java#L39-L48):

```java
if (jwtUtil.isTokenValid(token)) {
    String userId = jwtUtil.extractUserId(token);
    userRepository.findById(UUID.fromString(userId)).ifPresent(user -> { ... });
}
```

`extractUserId` valid token'ı yeniden parse eder — performans kaybı. Aynı token iki kez parse ediliyor. Ayrıca: token valid ama claims subject UUID değilse `UUID.fromString` throw eder, filter çağrısı 500 döner. Try-catch yok.

Düzeltme: tek parse + Claims object'ten alanları çek.

### 13. JwtUtil 'access' vs 'refresh' token claim'i ayırmıyor

Access ve refresh token aynı imza ile üretilir, sadece expiry farklı. Refresh token'ı access token gibi kullanıp `/api/v1/...` endpoint'lerine geçebilir (filter ayırmıyor). Pratikte refresh token Authorization header'ında nadiren gelir, ama kontrat ihlali.

Düzeltme: refresh token'a `"type": "refresh"` claim ekle, filter sadece `type=access` olanları kabul etsin.

### 14. SecurityConfig'de CORS yok

`SecurityFilterChain` config'inde `.cors(...)` çağrısı yok. Mobil farklı origin'den (development'ta `http://localhost:3000` web preview) çağırırsa preflight 401 alır.

Düzeltme:
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

`.cors(Customizer.withDefaults())` ekle.

### 15. CSRF disabled — OK ama yorum yok

JWT-only API için CSRF gereksiz. `.csrf(disable)` doğru ama yorum eklemek good practice.

### 16. Stateless session OK

`SessionCreationPolicy.STATELESS` — doğru, JWT modelinde sticky session gerek yok.

### 17. PasswordEncoder BCrypt OK

Default 10 round. Production'da 12+ daha güvenli ama 10 da kabul edilir.

### 18. `JwtAuthFilter` 'log.info("jwtAuthFilter")' her istekte yazıyor

[JwtAuthFilter:31](../src/main/java/kg/sportmanager/security/JwtAuthFilter.java#L31):

```java
log.info("jwtAuthFilter");
```

Production log spam. Debug seviyesine indir veya sil.

### 19. AuthController login request log'u

[AuthController:37](../src/main/java/kg/sportmanager/controller/AuthController.java#L37) — şifre log'a yazıyor. Bkz [01-auth-review.md](01-auth-review.md) #2.

### 20. Public path listesi swagger spam'i

[SecurityConfiguration:42-52](../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java#L42-L52) — `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/webjars/**` hepsi permit. Production'da Swagger'ı disable etmek opsiyonu eklenmeli (config flag).

---

## P2 — Orta

### 21. `Origin`, `Referer` validation yok

CSRF disable + JWT olduğundan API "any-origin" çalışır. Mobil için OK, web bridge için risk. CORS sıkı ayarlanırsa azalır.

### 22. Sensitive endpoint için rate limiting yok

Login brute force korumasız. Bucket4j veya Spring Cloud Gateway ile dakikalık limit ekle.

### 23. `User.password` ve `User.refreshToken` `@JsonIgnore` yok

`User` entity direct response olarak dönmüyor ama defansif olarak ekle:

```java
@JsonIgnore
private String password;

@JsonIgnore
private String refreshToken;
```

### 24. SQL injection riski

JPQL ile parametrize edilmiş — risk yok. Direct concat hiçbir yerde görmüyor. ✅

### 25. XSS riski

Backend JSON döndürdüğü için XSS direkt yok. Ama `cancelReason` ve `description` alanlarına `<script>` girilebilir; istemci o veriyi DOM'a yazarken kaçırırsa risk. Backend sanitize etmemeli (kontent veri olarak saklanır), istemci escape etmeli.

### 26. Mass assignment riski

DTO'lar entity'lerden ayrı → mass assignment yok. ✅

### 27. Email enumeration riski

`POST /auth/forgot-password` doc:
> Gizlilik için backend email kayıtlı mı kayıtsız mı ayrımı yapmadan 200 dönmeli.

Kod hiçbir şey yapmıyor, 200 döner ✓. Ama gerçek implementasyon yapılırken bu kural unutulmamalı — `EMAIL_NOT_FOUND` döndürmek enumeration vektörü.

`POST /auth/register` ise `409 EMAIL_ALREADY_USED` döner → enumeration mümkün. Doc kontratı zaten bunu kabul ediyor. Tolere edilir ama dikkat.

### 28. Password reset token TTL'i yok (yapılırsa)

`forgot-password` implement edilirken: reset link 15-30 dk içinde expire olmalı, tek kullanımlık olmalı.

### 29. `@PreAuthorize` annotation'ları yok

Tüm role check'ler manuel (`requireOwner`). Spring Security'nin built-in `@PreAuthorize("hasRole('OWNER')")` daha temiz olurdu ama mevcut yaklaşım da geçerli — CLAUDE.md "role checks in service" ilkesini koruyor.

---

## Eylem Önerileri

1. **Hata zarfını tek formata çek** — `AuthServiceImpl` `AppException` kullansın, EntryPoint/AccessDeniedHandler `ErrorResponse` döndürsün.
2. **`details: null` literal JSON** — `HashMap` veya `ErrorResponse` DTO'sunu kullan, `details` field'ını ekle.
3. **MESSAGES map'ini doldur** — eksik 20+ kod için en/ru/ky çevirileri.
4. **Properties dosyalarını kullan veya sil** — kararı netleştir.
5. **JWT secret env var'dan oku** — production'da fail-fast.
6. **UTF-8 belirt** `secret.getBytes(StandardCharsets.UTF_8)`.
7. **Expired vs invalid token ayrımı** — refresh flow için kritik.
8. **`@JsonIgnore`** User entity'ye.
9. **CORS config**.
10. **`log.info("jwtAuthFilter")` ve şifre log'u** — sil/kaldır.
11. **Access token type claim** — refresh token Authorization'a girmesin.
