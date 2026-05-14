# Auth Review

Doc kaynağı: [docs/auth-api.md](../docs/auth-api.md)  
Kod: [AuthController](../src/main/java/kg/sportmanager/controller/AuthController.java), [AuthServiceImpl](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java), [User](../src/main/java/kg/sportmanager/entity/User.java), [InviteCode](../src/main/java/kg/sportmanager/entity/InviteCode.java), [JwtUtil](../src/main/java/kg/sportmanager/security/JwtUtil.java), [SecurityConfiguration](../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java)

---

## Endpoint Uyum Matrisi

| Doc'taki yol                        | Doc'daki davranış                                       | Koddaki yol                                                | Uyum                                               |
| ----------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------- | -------------------------------------------------- |
| `POST /api/v1/auth/login`           | 200 + `{user, accessToken, refreshToken}`               | `POST /auth/login`                                         | ⚠️ **Yol farklı**, body uyumlu                     |
| `POST /api/v1/auth/register`        | 200 + auth body, OWNER/MANAGER discriminator + invite   | `POST /auth/register`                                      | ⚠️ Yol farklı, body uyumlu                         |
| `POST /api/v1/auth/refresh`         | 200, **sadece** `{accessToken, refreshToken}`           | `POST /auth/refresh` döndürdüğü `AuthResponse` user içerir | ❌ Doc ihlali                                      |
| `POST /api/v1/auth/logout`          | 200/204, boş body                                       | `POST /auth/logout`                                        | ✅ Yol farklı, davranış uyumlu                     |
| `POST /api/v1/auth/forgot-password` | Yeni şifre üret + email + 200                           | `POST /auth/forgot-password` — **hiç bir şey yapmıyor**    | ❌ Sahte endpoint                                  |
| `POST /api/v1/auth/invite-code`     | OWNER-only, `{code, expiresAt}` + **subscription gate** | `POST /auth/invite-code`                                   | ⚠️ Subscription gate yok, role check controller'da |

---

## P0 — Kritik

### 1. `forgot-password` sahte endpoint

[AuthController:70-72](../src/main/java/kg/sportmanager/controller/AuthController.java#L70-L72):

```java
public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
    return ResponseEntity.ok().build();
}
```

Hiçbir şey yapmıyor. Doc:

> Yeni şifre üretilir, kullanıcıya email ile gönderilir, kullanıcı o şifre ile login olur, sonra şifre değiştirme akışına girer.

Sonuç: production'a girerse "şifremi unuttum" tuşu sessizce başarısız olur. Ya `SERVICE_UNAVAILABLE` döndür ya gerçek email akışını implement et (Spring Mail + JavaMailSender + temp password). Minimum: en azından kullanıcının email'i kayıtlı mı kontrolü, sonra deterministik 422/404 dön.

### 2. Şifre log'a yazılıyor

[AuthController:37](../src/main/java/kg/sportmanager/controller/AuthController.java#L37):

```java
log.info("Login request: {}", request);
```

`LoginRequest` `@Data` + `toString()` ile `username` ve `password` alanlarını ifşa eder:

```
2026-05-14 ... Login request: LoginRequest(username=test@x.com, password=Test1234)
```

**Acil düzeltme:** `password` alanına `@ToString.Exclude` veya `LoginRequest`'i tamamen log'lama. Aynı kontrol register için de gerekli (`RegisterRequest.password` ve `inviteCode` ifşa olur).

### 3. Yol prefix uyumsuzluğu

Docs tüm auth endpoint'leri için `/api/v1/auth/**` yazıyor. Kod `/auth/**`. Mobil ekip docs'tan implementasyon yapıyor → 404. İki opsiyon:

- A) Controller'ı `/api/v1/auth` ile re-map et (doğru olan)
- B) Docs'u `/auth/**` ile güncelle ve [SecurityConfiguration permitAll listesini](../src/main/java/kg/sportmanager/configuration/SecurityConfiguration.java#L43-L46) zaten doğru

Öneri: **A**. Aynı prefix'i tüm endpoint'ler için tut.

---

## P1 — Yüksek

### 4. Refresh response user içeriyor

[AuthServiceImpl.buildAuthResponse:120-137](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L120-L137) her zaman `user`'ı dolduruyor. Docs (refresh §):

> 200 OK — response body  
> **Sadece tokenlar döner; user alanı yoktur.**

Düzeltme: `refresh` için ayrı bir `TokenPairResponse` DTO'su veya `buildAuthResponse(user, boolean includeUser)`.

### 5. Hata zarfı multilingual değil

`ResponseStatusException(HttpStatus.X, "ERROR_CODE")` Spring'in default body'sini üretir:

```json
{
  "timestamp": "...",
  "status": 401,
  "error": "Unauthorized",
  "message": "INVALID_CREDENTIALS",
  "path": "/auth/login"
}
```

Docs ve diğer servisler (Home/Session/Reports) `{code, message: {en, ru, ky}}` zarfını döner. Sonuç: istemcide tek bir error parser yazılamaz. Düzeltme:

```java
// AuthServiceImpl içinde:
throw new AppException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
```

Sonra [GlobalExceptionHandler.MESSAGES](../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L13)'a şu kodlar eklenmeli:

- `INVALID_CREDENTIALS`
- `ACCOUNT_LOCKED`
- `INVALID_INVITE_CODE`
- `EMAIL_ALREADY_USED`
- `PHONE_ALREADY_USED`
- `SESSION_EXPIRED` (refresh path)
- `UNAUTHORIZED` (genel)

### 6. Invite-code role check controller'da

[AuthController.inviteCode:79-84](../src/main/java/kg/sportmanager/controller/AuthController.java#L79-L84):

```java
if (user.getRole() != User.Role.OWNER) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
}
```

[CLAUDE.md](../CLAUDE.md) açık kural:

> Role checks (OWNER vs MANAGER) are **not** enforced by URL patterns — they happen inside service methods.

`ResponseStatusException(403)` ayrıca multilingual body üretmez. Düzeltme: `AuthServiceImpl.generateInviteCode` başında `if (user.getRole() != OWNER) throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);`.

### 7. Invite-code'da subscription gate yok

Docs ([auth-api.md §6](../docs/auth-api.md#6-post-apiv1authinvite-code)):

> 403 `SUBSCRIPTION_REQUIRED` — Owner aboneliği `EXPIRED` veya `GRACE@0`

Subscription modülü hiç yok → bu kural pas geçiliyor. (Subscription tamamen eksik; bkz. [06-subscription-review.md](06-subscription-review.md).)

### 8. Manager↔Owner ilişkisi register'da kaybediliyor

[AuthServiceImpl.register:73-81](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L73-L81):

```java
User user = User.builder()
    .name(...)
    .role(request.getRole())
    .build();
userRepository.save(user);
```

Manager kaydedildiğinde `invite.getOwner()` biliniyor (InviteCode entity'sinde tutuluyor) ama yeni Manager User'a **kaydedilmiyor**. `User` entity'de zaten `owner` alanı yok. Sonuç:

- Manager hangi owner'a bağlı bilinmez
- HomeService manager için yanlış venue listesi döner (kendisinin venue'leri = boş)
- Reports'ta manager filtrelenemez

**Düzeltme bütünü:**

1. `User` entity'ye `@ManyToOne private User owner;` ekle (nullable, OWNER için null).
2. `AuthServiceImpl.register`'da MANAGER için `user.setOwner(invite.getOwner())`.
3. `HomeServiceImpl.resolveOwner` ve `SessionServiceImpl.validateTableAccess`'ı bu alanı kullanacak şekilde düzelt.

### 9. JWT secret'i HMAC için minimum bayt kontrolü

[JwtUtil.getKey():24-26](../src/main/java/kg/sportmanager/security/JwtUtil.java#L24-L26):

```java
return Keys.hmacShaKeyFor(secret.getBytes());
```

Platform default charset kullanıyor. Karakter setine duyarlı bir secret'ı (Latin1 vs UTF-8) farklı yerlerde farklı imzalayabilir. **`StandardCharsets.UTF_8` belirt.**

Ek: HS512 imzası için secret >= 64 byte gerekir. application.yml'deki secret 46 karakter. `signWith` default'u jjwt 0.11.5'te SHA-512 mi SHA-256 mı? `signWith(getKey())` → key boyutuna göre otomatik (HS256 için 32 byte yeter). 46 byte yeterli HS256'ya. OK ama açıkça `SignatureAlgorithm.HS256` belirtmek kontratı netleştirir.

### 10. Refresh akışında "stored token vs sent token" karşılaştırması zayıf

[AuthServiceImpl.refresh:85-95](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L85-L95):

```java
User user = userRepository.findByRefreshToken(request.getRefreshToken())
        .orElseThrow(() -> new ResponseStatusException(401, "SESSION_EXPIRED"));
if (!jwtUtil.isTokenValid(request.getRefreshToken())) {
    throw new ResponseStatusException(401, "SESSION_EXPIRED");
}
return buildAuthResponse(user);
```

İyi yanı: User'ı `findByRefreshToken` ile arıyor — yani DB'deki refresh token ile body'deki eşleşmek zorunda. **Ama:** Aynı refresh token'ı iki kez kullanmak mümkün:

1. Cihaz A `/refresh` çağırır → yeni refresh `R2` üretilir, `user.refreshToken = R2` set edilir.
2. Cihaz A bağlantı sorunundan yanıt alamaz, eski refresh `R1` ile tekrar dener → DB'de `R1` artık yok → 401, sonsuz döngü kırılır. OK.

Ancak ek edge case: race condition. İki paralel `/refresh` çağrısı aynı `R1` için → biri eski R1'i bulur, R2 yazar; diğeri de R1'i bulamamış olabilir → biri başarısız. Tolere edilebilir.

**Asıl sorun:** Refresh token rotation güvenlik açısından "burned token detection" gerektirir. Eski refresh token tekrar gelirse → token çalınmış olabilir → tüm session'ları iptal. Bu MVP için scope dışı ama not edilmeli.

---

## P2 — Orta

### 11. Email/phone format validation yok

`RegisterRequest`'te `@Email`, `@Pattern` yok. `email` alanına "abc" geçilirse hata vermez, DB'de saklanır, sonradan login başarısız olur. Doc tablo `phone: E.164-ish (+996 ...)` diyor.

### 12. Şifre karmaşıklığı kontrolü yok

`password: "1"` kabul edilir. Minimum uzunluk, karakter çeşitliliği yok. `@Size(min=8)` minimum.

### 13. Account locked manuel set edilemiyor

`User.locked` alanı var ama hiç kimse `true` set etmiyor. Brute force koruması yok. Docs `423 ACCOUNT_LOCKED` belirtiyor — backend hesabı ne zaman kilitliyor? Kod yok.

### 14. Invite code spam korumalı değil

Owner istediği kadar `POST /auth/invite-code` çağırabilir → DB'de sürekli birikir. Kullanılmamış + süresi geçmemiş kod varsa yeni üretme ya da rate limit ekle.

### 15. Invite code "owner OWNER mi" kontrolü yok

[AuthServiceImpl.generateInviteCode:102](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L102) `owner` parametre olarak gelse de role kontrolü controller'da. Controller bypass edilirse (örn. başka servis çağırırsa) sorun. Kontrolü servise taşıyınca düzelir (madde #6).

### 16. Invite code response `expiresAt` formatı tutarsız

[AuthServiceImpl:114-116](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L114-L116):

```java
.expiresAt(expiresAt.toString())  // LocalDateTime.toString() → "2026-05-21T12:34:56.123"
```

Docs: `expiresAt: "2026-05-04T12:34:56Z"` (UTC, Z suffix). `LocalDateTime` zone bilgisi tutmuyor → "Z" yok. UTC istiyorsak `Instant.now().plus(7, DAYS)` veya `OffsetDateTime` kullan, ya da DTO'da `Instant` tut + Jackson serialize etsin.

### 17. `User.getUsername()` email döner

```java
@Override
public String getUsername() { return email; }
```

UserDetails sözleşmesi için OK. Ama [ReportsRepository.managerStats](../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L156-L170)'te `s.manager.email AS username` projeksiyona giriyor. Docs managers/reports için ayrı `username` alanı tanımlıyor (`@aibek` gibi). User entity'sinde `username` field'ı tamamen eksik. Bkz [04-reports-review.md](04-reports-review.md).

### 18. `AuthResponse.user.role` String, "OWNER"/"MANAGER" UPPERCASE

OK — `role(user.getRole().name())` enum name'i veriyor. Doc kontratı sağlanıyor.

---

## P3 — Düşük / Kod Kalitesi

- `AuthController` Swagger description'ları Rusça, geri kalan kod İngilizce/Türkçe karışık.
- `inviteCodeRepository.findByOwner(User owner)` metod tanımlı ama nerede çağrıldığı yok. Dead code.
- `User.locked` field'ı Lombok `@Data` ile `isLocked()` ve `setLocked()` getter/setter üretir; `UserDetails.isAccountNonLocked()` `!locked` döner — OK.
- `User` entity'de `@JsonIgnore` yok — controller direct dönerse password/refreshToken ifşa olur. `User` direct response olarak hiç dönmüyor (UserResponse var) ama defansif olarak eklenmeli.
- `InviteCode.used` flag'ı set ediliyor ama `findByCodeAndUsedFalse` haricinde sorgu yok. Süresi geçmiş kodlar `used=false` kalır, asla temizlenmez. Cleanup cron yok (opsiyonel).
- `inviteCode` alanı log'lara da girmemeli (madde #2 ile aynı çözüm).

---

## Eylem Önerileri (Sırasız)

1. **Hata zarfını birleştir** — `AuthServiceImpl` `AppException` kullansın, `MESSAGES` map'ine eksikleri ekle.
2. **`/auth/**`→`/api/v1/auth/**`** veya docs'u güncelle. Tek karar.
3. **`@ToString.Exclude` ekle** veya log seviyesi düşür / payload log'u kaldır.
4. **`forgot-password`'ı gerçekten implement et** veya `SERVICE_UNAVAILABLE` döndür.
5. **`User.owner` field'ı ekle** ve register'da set et.
6. **`refresh` response'tan `user` field'ı çıkar.**
7. **Role check'i servis katmanına taşı** (`generateInviteCode` başına).
8. **Subscription gate hook'u** — eklenince [SubscriptionGuard / @PreAuthorize](06-subscription-review.md) ile `generateInviteCode`'a uygula.
9. **JWT secret env var'dan oku** — `${JWT_SECRET:default}` syntax ile. Üretim secret'ı commit edilmemeli.
10. **Email + phone validation, password complexity, lockout-on-fail** — minimum.
