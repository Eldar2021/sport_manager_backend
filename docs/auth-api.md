# Auth REST API — Backend Contract

Mobil istemcinin (`packages/auth`) backend'den beklediği auth uçları, request/response gövdeleri, header sözleşmesi ve hata akışı.

**Base URL:** `<BASE_URL>` (mobil tarafta `--dart-define=BASE_URL=...` ile geliyor)
**Content-Type:** `application/json; charset=utf-8`

## Global headers (her istekte)

| Header            | Source                                                       | Example             | Required    |
| ----------------- | ------------------------------------------------------------ | ------------------- | ----------- |
| `Accept-Language` | Cihaz dili (`en` / `ru` / `ky`)                              | `ru`                | Optional    |
| `versionBuild`    | App build number                                             | `42`                | Optional    |
| `os`              | Platform                                                     | `ios` / `android`   | Optional    |
| `Authorization`   | `Bearer <accessToken>` — sadece authenticated endpoint'lerde | `Bearer eyJhbGc...` | Conditional |

> Login / register / forgot-password endpoint'leri **non-auth Dio instance** ile çağrılır → `Authorization` header'ı yoktur.
> Refresh, logout, invite-code → **bearer instance** üzerinden gider → `Authorization` header'ı eklenir.

---

## 1. POST `/api/v1/auth/login`

**Auth:** none

### Request body

```json
{
  "username": "test",
  "password": "Test1234"
}
```

| Field      | Type   | Notes                                          |
| ---------- | ------ | ---------------------------------------------- |
| `username` | string | Email, telefon ya da username — backend kararı |
| `password` | string | Düz metin (TLS)                                |

### 200 OK — response body

```json
{
  "user": {
    "id": "user-001",
    "name": "Test Owner",
    "role": "OWNER", // should be role "OWNER" or "MANAGER"
    "email": "test@tableflow.kg",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

### Errors

| HTTP | `code`                | Trigger                    |
| ---- | --------------------- | -------------------------- |
| 401  | `INVALID_CREDENTIALS` | Yanlış username / password |
| 423  | `ACCOUNT_LOCKED`      | Hesap kilitli              |

> İstemci 401'i login üzerinde özel ele alır: refresh denemez, doğrudan logout / hata gösterir.

---

## 2. POST `/api/v1/auth/register`

**Auth:** none

İki tipte body gelir — `role` alanı discriminator'dır.

### 2a) Owner kayıt

```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "+996 700 000 003",
  "password": "Test1234",
  "role": "OWNER"
}
```

### 2b) Manager kayıt (invite kodu zorunlu)

```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+996 700 000 004",
  "password": "Test1234",
  "role": "MANAGER",
  "inviteCode": "INVITE-001"
}
```

| Field        | Type   | Required              | Notes                         |
| ------------ | ------ | --------------------- | ----------------------------- |
| `name`       | string | yes                   |                               |
| `email`      | string | yes                   |                               |
| `phone`      | string | yes                   | E.164-ish (`+996 ...`)        |
| `password`   | string | yes                   |                               |
| `role`       | enum   | yes                   | `OWNER` \| `MANAGER`          |
| `inviteCode` | string | role=MANAGER iken yes | Owner içinse field hiç gelmez |

### 200 OK — response body

Login ile aynı: `{ user, accessToken, refreshToken }`.

```json
{
  "user": {
    "id": "user-001",
    "name": "Test Owner",
    "role": "OWNER", // should be role "OWNER" or "MANAGER"
    "email": "test@tableflow.kg",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

### Errors

| HTTP | `code`                | Trigger                                |
| ---- | --------------------- | -------------------------------------- |
| 400  | `INVALID_INVITE_CODE` | Manager için kod yok / yanlış / expire |
| 409  | `EMAIL_ALREADY_USED`  | Email zaten kayıtlı                    |
| 409  | `PHONE_ALREADY_USED`  | Telefon zaten kayıtlı                  |
| 422  | validation            | Field-level validation                 |

---

## 3. POST `/api/v1/auth/refresh`

**Auth:** none (refresh işlemi için access token zorunlu değil — backend yalnızca body'deki `refreshToken`'a güvenir).

> İstemci bu endpoint'i bearer Dio instance'ı üzerinden çağırdığı için `Authorization: Bearer <accessToken>` header'ı **gönderilebilir** (özellikle access token henüz expire olmamışsa). Backend bu header'ı **yok sayar** — refresh kararını sadece body'deki `refreshToken` üzerinden verir. Access token süresi dolduğunda da aynı endpoint çağrılır; header'ın expired bir token taşıması engelleyici olmamalıdır.

### Request body

```json
{
  "refreshToken": "eyJhbGc..."
}
```

### 200 OK — response body

**Sadece tokenlar döner; user alanı yoktur.**

```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

> İstemci her iki token'ı da yeniden saklar — refresh token rotation destekleniyorsa backend yeni refresh token üretmeli (önerilir).

### Errors

| HTTP | Trigger                        | İstemci davranışı                               |
| ---- | ------------------------------ | ----------------------------------------------- |
| 401  | Refresh token geçersiz/expired | Local state temizlenir, login ekranına yönlenir |

> Refresh sırasında 401 dönerse istemci **yeniden refresh denemez** — sadece logout tetiklenir.

---

## 4. POST `/api/v1/auth/logout`

**Auth:** required (`Authorization: Bearer <accessToken>`)

### Request body

Boş (`{}` ya da hiç body).

### 200 OK / 204 No Content

Body beklenmiyor.

### Notlar

- İstemci local token'ı **önce** temizler, sonra remote'a istek atar; backend hatası swallow edilir.
- Backend yine de refresh token'ı invalidate etmeli (server-side blacklist).
- 401 dönerse istemci umursamaz — zaten logout ediliyor.

---

## 5. POST `/api/v1/auth/forgot-password`

**Auth:** none

### Request body

```json
{
  "email": "user@example.com"
}
```

### 200 OK

Body önemsiz. Gizlilik için backend **email kayıtlı mı kayıtsız mı** ayrımı yapmadan 200 dönmeli (enumeration'a karşı). Gonderilen email'e yeni şifre gönderilir. O Sifre ile kullanıcı yeniden giriş yapar. Sonrasında şifresini değiştirmesi istenir.

### Errors

| HTTP | Trigger                                                |
| ---- | ------------------------------------------------------ |
| 422  | Email format invalid (yine de soft-fail tercih edilir) |
| 404  | Email not found                                        |
| 400  | Account is not active                                  |

---

## 6. POST `/api/v1/auth/invite-code`

**Auth:** required, role = `OWNER`. Owner olmayan kullanıcı 403 almalı.

### Request body

Boş.

### 200 OK — response body

```json
{
  "code": "INVITE-001",
  "expiresAt": "2026-05-04T12:34:56Z"
}
```

| Field       | Type             | Notes                                       |
| ----------- | ---------------- | ------------------------------------------- |
| `code`      | string           | Manager kayıt sırasında kullanılır          |
| `expiresAt` | ISO-8601 \| null | Süre yoksa `null`. UTC olması tercih edilir |

### Errors

| HTTP | `code`                  | Trigger                                                |
| ---- | ----------------------- | ------------------------------------------------------ |
| 401  | —                       | Token yok/expired                                      |
| 403  | `FORBIDDEN`             | Owner değil                                            |
| 403  | `SUBSCRIPTION_REQUIRED` | Owner aboneliği `EXPIRED` veya `GRACE@0` (yazma gate) |

> `SUBSCRIPTION_REQUIRED` global gate kuralı için bkz. [subscription-api.md § Subscription gate](subscription-api.md#subscription-gate--diğer-endpointlere-etkisi).

---

## Models — Alan referansı

### `UserModel`

```json
{
  "id": "user-001",
  "name": "Test Owner",
  "role": "OWNER", // OWNER or MANAGER
  "email": "test@tableflow.kg",
  "phone": "+996 700 000 001"
}
```

| Field   | Type              | Required | Notes                                        |
| ------- | ----------------- | -------- | -------------------------------------------- |
| `id`    | string            | yes      | Server-generated, stable                     |
| `name`  | string            | yes      |                                              |
| `role`  | `OWNER`/`MANAGER` | yes      | UPPERCASE — JSON value enum'a karşılık gelir |
| `email` | string \| null    | no       |                                              |
| `phone` | string \| null    | no       |                                              |

### `AuthTokensModel`

```json
{ "accessToken": "...", "refreshToken": "..." }
```

### `AuthResultModel` = `UserModel` + `AuthTokensModel` (flat)

```json
{ "user": { ... }, "accessToken": "...", "refreshToken": "..." }
```

### `InviteCodeModel`

```json
{ "code": "INVITE-001", "expiresAt": "2026-05-04T12:34:56Z" }
```

---

## Error response formatı (öneri)

Backend tüm hata yanıtlarını tutarlı bir zarfla göndermeli — istemci tarafında `AppException` / `AuthException` mapping kolay olsun:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": {
    "en": "Invalid username or password",
    "ru": "Неверный логин или пароль",
    "ky": "Логин же сырсөз туура эмес"
  },
  "details": null
}
```

| Field     | Type                            | Notes                                                                                                  |
| --------- | ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `code`    | string (UPPER_SNAKE)            | İstemcide `AuthErrorCode` enum'una map edilir                                                          |
| `message` | object `{en, ru, ky}` \| string | Tercihen üç dil; tek dilse `Accept-Language`'i kullanır                                                |
| `details` | array \| null                   | Validation hatalarında `[{field, rule, message}]` dolar; diğer durumlarda `null` (bkz. home_page_api.md) |

İstemcideki `AuthErrorCode` enum'una göre backend code map'i:

| Backend `code`        | İstemci `AuthErrorCode` | Tipik HTTP         |
| --------------------- | ----------------------- | ------------------ |
| `INVALID_CREDENTIALS` | `invalidCredentials`    | 401                |
| `INVALID_INVITE_CODE` | `invalidInviteCode`     | 400                |
| `SESSION_EXPIRED`     | `sessionExpired`        | 401 (refresh path) |
| `ACCOUNT_LOCKED`      | `accountLocked`         | 423                |
| (anything else)       | `unknown`               | 4xx/5xx            |

> 401 = "session expired → login'e dön", 423 = "account locked banner". İkisi de istemcide `UnauthenticatedExceptionHandle` üzerinden globalde işleniyor.

---

## Refresh token flow (özet)

```
İstemci → herhangi bir bearer endpoint → 401
         ↓
İstemci → POST /auth/refresh { refreshToken }
   ├─ 200 → { accessToken, refreshToken } sakla, orijinal isteği yeni token'la tekrar et
   └─ 401 → local state temizle, login'e yönlen (refresh DENENMEZ)
```

Backend için kritik:

- 401 sadece **gerçekten** access token expired/invalid olduğunda dönsün — yetki/forbidden için **403** kullanın. Aksi halde istemci refresh döngüsüne girer.
- Refresh endpoint'i 401 dönerse istemci tek seferde durur (recursive refresh yok).
- Refresh token rotation (her refresh'te yeni refresh) tavsiye edilir; istemci zaten yeni refresh token'ı saklıyor.

---
