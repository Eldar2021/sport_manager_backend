# Auth REST API — Backend Contract

Mobil istemcinin (`packages/auth`) backend'den beklediği auth uçları, request/response gövdeleri, header sözleşmesi ve hata akışı.

**Base URL:** `<BASE_URL>` (mobil tarafta `--dart-define=BASE_URL=...` ile geliyor)
**Content-Type:** `application/json; charset=utf-8`

> **Status code kuralı — 401 yalnız expired için.**
> Backend'te `401` döner **yalnızca** access ya da refresh token **expired** olduğunda.
> Diğer auth-fail durumlarının tamamı (`INVALID_CREDENTIALS`, `INVALID_TOKEN`,
> `INVALID_TOKEN_TYPE`, `UNAUTHORIZED`, `LOGOUT_FAILED`) `400 Bad Request` döner.
> Mobile refresh-akış'ı yalnızca `401 SESSION_EXPIRED` görürse refresh tetiklemelidir;
> `400`'ler içinden yalnız `SESSION_EXPIRED` kodu refresh için anlamlıdır.

> **İlişkili docs:** Login sonrası kullanıcı profil özeti için
> [profile-api.md](profile-api.md) (yeni endpoint — `GET /api/v1/profile`).

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

| HTTP | `code`                | Trigger                                                                  |
| ---- | --------------------- | ------------------------------------------------------------------------ |
| 400  | `INVALID_CREDENTIALS` | Yanlış email/phone veya parola — mesaj: "Email or password is incorrect" |
| 423  | `ACCOUNT_LOCKED`      | Hesap kilitli                                                            |
| 422  | `VALIDATION_ERROR`    | Body field validation                                                    |

> **Login `401` döndürmez.** 401 yalnız expired token için ayrılmıştır. Yanlış
> credentials → 400 `INVALID_CREDENTIALS`. İstemci bu kodu özel ele alır: refresh
> denemez, doğrudan kullanıcıya hata gösterir.

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

| HTTP | `code`               | Trigger                                                   | İstemci davranışı                   |
| ---- | -------------------- | --------------------------------------------------------- | ----------------------------------- |
| 401  | `SESSION_EXPIRED`    | Refresh token **expired**                                 | Local state temizle, login'e yönlen |
| 400  | `INVALID_TOKEN`      | Token bozuk / imza geçersiz / DB'de yok (revoke/rotation) | Local state temizle, login'e yönlen |
| 400  | `INVALID_TOKEN_TYPE` | Access token /refresh'a gönderildi                        | Local state temizle, login'e yönlen |
| 422  | `VALIDATION_ERROR`   | Body field validation (boş `refreshToken`)                | Bug; production'da görünmemeli      |

> **Sadece `401 SESSION_EXPIRED` "yeniden login gerek" sinyalidir.** 400'ler de
> aynı sonucu doğurur (token tekrar kullanılamaz) — fark sadece tanılama içindir.
> Hiçbir koşulda refresh **yeniden** refresh denemez.

---

## 4. POST `/api/v1/auth/logout`

**Auth:** opsiyonel — endpoint `permitAll`'dır. Bearer geçerli access token
ile gönderilirse backend kullanıcıyı tanır ve DB'deki `refreshToken` alanını
temizler. Token yok / bozuk / expired ise backend `400 LOGOUT_FAILED` döner.

> **Logout asla `401` döndürmez.** Success → `200`, başarısız → `400`. Bu
> kural mobile'ın "logout sırasında 401 alırsam refresh denemeyeyim" akışını
> sadeleştirir.

### Request body

Boş (`{}` ya da hiç body).

### 200 OK

Body yok. Backend `User.refreshToken = null` set eder (server-side revoke).

### Errors

| HTTP | `code`          | Trigger                                        |
| ---- | --------------- | ---------------------------------------------- |
| 400  | `LOGOUT_FAILED` | Token yok / bozuk / refresh-type / **expired** |

> Expired access token bile logout'ta `400 LOGOUT_FAILED` döner — `401 SESSION_EXPIRED`
> **değil**. Mobile zaten logout edildiği için tepki gereksiz.

### Notlar

- İstemci local token'ı **önce** temizler, sonra remote'a istek atar; backend
  hatası (`400 LOGOUT_FAILED`) swallow edilir.
- Server-side refresh token invalidation otomatik gerçekleşir (DB'de `null` set).
- Access token JWT olduğu için kendi başına revoke edilemez; mobile local
  silinmesine güvenir.

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

| HTTP | `code`                  | Trigger                                               |
| ---- | ----------------------- | ----------------------------------------------------- |
| 400  | `UNAUTHORIZED`          | Token yok / bozuk                                     |
| 401  | `SESSION_EXPIRED`       | Access token **expired**                              |
| 403  | `FORBIDDEN`             | Owner değil                                           |
| 403  | `SUBSCRIPTION_REQUIRED` | Owner aboneliği `EXPIRED` veya `GRACE@0` (yazma gate) |

> `SUBSCRIPTION_REQUIRED` global gate kuralı için bkz. [subscription-api.md § Subscription gate](subscription-api.md#subscription-gate--diğer-endpointlere-etkisi).

---

## 7. POST `/api/v1/auth/update-password`

**Auth:** required (`Authorization: Bearer <accessToken>`) — OWNER + MANAGER her ikisi.

Authenticated kullanıcının parolasını günceller. Eski parolayı doğrular,
yeniyi `bcrypt`'le hash'leyip kaydeder ve **yeni bir token pair** döndürür
(rotation: önceki refresh token DB'de invalidate edilir).

### Request body

```json
{
  "oldPassword": "OldPass12",
  "newPassword": "NewPass99"
}
```

| Field         | Type   | Required | Rules                |
| ------------- | ------ | :------: | -------------------- |
| `oldPassword` | string |   yes    | NotBlank             |
| `newPassword` | string |   yes    | NotBlank, 8–100 char |

### 200 OK — response body

```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

> `user` alanı dönmez (login/refresh response'larıyla aynı sözleşme).
> Mobile yeni token pair'i sakler; eski refresh token artık geçersizdir.

### Errors

| HTTP | `code`                | Trigger                                  |
| ---- | --------------------- | ---------------------------------------- |
| 400  | `INVALID_CREDENTIALS` | `oldPassword` yanlış                     |
| 400  | `UNAUTHORIZED`        | Token yok / bozuk                        |
| 401  | `SESSION_EXPIRED`     | Access token **expired**                 |
| 422  | `VALIDATION_ERROR`    | `newPassword < 8 char`, boş alanlar, vb. |

> Bu endpoint genelde `forgot-password` ile birlikte kullanılır: kullanıcı email
> üzerinden yeni şifre alır, onunla login olur, sonra `update-password` ile
> kendi seçtiği şifreye geçer.

---

## 8. DELETE `/api/v1/auth/account`

**Auth:** required (`Authorization: Bearer <accessToken>`) — OWNER + MANAGER her ikisi.

App Store / Play Store uyumluluğu için kullanıcı kendi hesabını silebilir.
Davranış role'e göre farklıdır:

| Role      | Davranış                                                                                                                                                                                                                                                                                                 |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OWNER`   | **Cascade hard-delete.** Owner'a ait tüm veri tamamen silinir: venues, tables, sessions, invite codes, subscriptions, payments, manager kullanıcıları **ve** owner satırının kendisi. Email/phone tekrar register için serbest kalır.                                                                    |
| `MANAGER` | **Soft-delete + PII anonymization.** `deletedAt = now`, `email = null`, `phone = null`, `refreshToken = null`, `locked = true`. `id`, `name`, `handle`, `owner` **korunur** ki owner'ın reports'unda manager'ın geçmişi görünmeye devam etsin. Aynı email/phone tekrar register edilebilir (fresh UUID). |

### Request

Body yok. Yalnız `Authorization` header'ı.

### 200 OK

Body yok. İşlem başarılı.

> Mobile bu yanıttan sonra local token'ı temizleyip kullanıcıyı login ekranına
> yönlendirmelidir. Owner için token zaten geçersizdir (kullanıcı satırı silindi).
> Manager için `locked = true` ve `refreshToken = null` set edilmiştir;
> ileride bu token ile yapılacak istekler login'e zorlayacak.

### Errors

| HTTP | `code`            | Trigger                              |
| ---- | ----------------- | ------------------------------------ |
| 400  | `UNAUTHORIZED`    | Token yok                            |
| 400  | `INVALID_TOKEN`   | Token bozuk / imza geçersiz / revoke |
| 401  | `SESSION_EXPIRED` | Access token **expired**             |

### Önemli notlar

- **Re-registration:** Owner silindikten sonra aynı email'le yeni OWNER kaydı yapılabilir; tamamen sıfır state ile başlar (yeni TRIAL otomatik oluşur). Manager için de aynı şey geçerli — yeni invite kodu ile aynı email/phone tekrar register edilebilir, yeni UUID alır.
- **Reports korunması (MANAGER):** Silinmiş manager hâlâ `Session.managerId` ile geçmiş session'lara bağlıdır; reports endpoint'leri (`/api/v1/reports/managers`, `/api/v1/reports/managers/{id}`) bu manager'ın geçmiş performansını göstermeye devam eder. `name` ve `handle` (display alanları) korunduğu için UI'da bilinmeyen kullanıcı görünmez.
- **OWNER → MANAGER cascade:** OWNER silindiğinde altındaki **tüm manager hesapları da hard-delete edilir** (tasarım kararı: owner'ın verisi gittikten sonra manager'ların verisi de pratikte kalmadığı için, onları da silmek doğru).
- **Audit log:** Backend `INFO` seviyesinde log atar (`Cascade-deleted OWNER id=...` / `Soft-deleted MANAGER id=...`). Şu an ayrı bir audit tablosu yok; istenirse v2'de eklenebilir.

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

Backend tüm hata yanıtlarını tutarlı bir zarfla gönderir — istemci tarafında `AppException` / `AuthException` mapping kolay olsun:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": {
    "en": "Email or password is incorrect",
    "ru": "Email или пароль неверны",
    "ky": "Email же сырсөз туура эмес"
  },
  "details": null
}
```

| Field     | Type                            | Notes                                                                                                    |
| --------- | ------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `code`    | string (UPPER_SNAKE)            | İstemcide `AuthErrorCode` enum'una map edilir                                                            |
| `message` | object `{en, ru, ky}` \| string | Tercihen üç dil; tek dilse `Accept-Language`'i kullanır                                                  |
| `details` | array \| null                   | Validation hatalarında `[{field, rule, message}]` dolar; diğer durumlarda `null` (bkz. home_page_api.md) |

İstemcideki `AuthErrorCode` enum'una göre backend code map'i:

| Backend `code`        | İstemci `AuthErrorCode` | Tipik HTTP                                                            |
| --------------------- | ----------------------- | --------------------------------------------------------------------- |
| `INVALID_CREDENTIALS` | `invalidCredentials`    | **400** (yanlış email/parola)                                         |
| `INVALID_INVITE_CODE` | `invalidInviteCode`     | 400                                                                   |
| `INVALID_TOKEN`       | `unknown`               | 400 (bozuk / revoke edilmiş token)                                    |
| `INVALID_TOKEN_TYPE`  | `unknown`               | 400 (yanlış tip — access /refresh'a, refresh / authenticated-route'a) |
| `LOGOUT_FAILED`       | `unknown`               | 400 (logout sırasında token yok/bozuk/expired)                        |
| `UNAUTHORIZED`        | `unknown`               | 400 (token yok)                                                       |
| `SESSION_EXPIRED`     | `sessionExpired`        | **401** — _yalnızca_ expired access/refresh için                      |
| `ACCOUNT_LOCKED`      | `accountLocked`         | 423                                                                   |
| (anything else)       | `unknown`               | 4xx/5xx                                                               |

> **`401` ↔ expired token bire-bir eşleşmedir.** Yetki/forbidden için `403`,
> diğer client hataları için `400`. Bu sayede mobile refresh akışı net: yalnız
> `401 SESSION_EXPIRED` refresh tetikler, diğer 4xx kodları sessizce yutulur
> ya da kullanıcıya gösterilir.

---

## Status code kuralı (401 yalnız expired için)

Backend tüm auth-fail durumlarını aşağıdaki şekilde ayrıştırır:

| Durum                                                         | HTTP | `code`                           |
| ------------------------------------------------------------- | ---- | -------------------------------- |
| Access token expired                                          | 401  | `SESSION_EXPIRED`                |
| Refresh token expired                                         | 401  | `SESSION_EXPIRED`                |
| Login yanlış email/parola                                     | 400  | `INVALID_CREDENTIALS`            |
| Update-password yanlış eski parola                            | 400  | `INVALID_CREDENTIALS`            |
| Bearer header yok / token bozuk                               | 400  | `UNAUTHORIZED` / `INVALID_TOKEN` |
| Refresh token /authenticated-route'a gönderildi               | 400  | `INVALID_TOKEN_TYPE`             |
| Access token /refresh'a gönderildi                            | 400  | `INVALID_TOKEN_TYPE`             |
| Refresh token DB'de yok (rotated/logout)                      | 400  | `INVALID_TOKEN`                  |
| Logout: token yok/bozuk/expired                               | 400  | `LOGOUT_FAILED`                  |
| Delete-account başarılı (OWNER cascade / MANAGER soft-delete) | 200  | —                                |
| OWNER-only endpoint'i MANAGER çağırdı                         | 403  | `FORBIDDEN`                      |
| Subscription gate (EXPIRED/GRACE@0)                           | 403  | `SUBSCRIPTION_REQUIRED`          |

---

## Refresh token flow (özet)

```
İstemci → herhangi bir bearer endpoint → 401 SESSION_EXPIRED
         ↓
İstemci → POST /auth/refresh { refreshToken }
   ├─ 200                  → { accessToken, refreshToken } sakla, orijinal isteği tekrar et
   ├─ 401 SESSION_EXPIRED  → local state temizle, login'e yönlen (refresh DENENMEZ)
   └─ 400 INVALID_TOKEN /
         INVALID_TOKEN_TYPE → local state temizle, login'e yönlen (refresh DENENMEZ)
```

Backend için kritik:

- `401` **sadece** access/refresh token expired olduğunda. Bozuk/yanlış-tip/revoke → 400.
- Refresh endpoint'i 401 ya da 400 dönerse istemci tek seferde durur (recursive refresh yok).
- Refresh token rotation aktif: her refresh'te DB'deki `User.refreshToken` yenilenir; eski refresh artık geçersiz.

---
