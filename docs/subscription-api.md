# Subscription REST API — Backend Contract

Mobil istemcinin (`packages/subscription`) backend'den beklediği abonelik (uyelik) uçları, request/response gövdeleri ve hata akışı. Ürün akışı için bkz. [user-flow/subscription-flow.md](../user-flow/subscription-flow.md).

**Base URL:** `<BASE_URL>` (mobil tarafta `--dart-define=BASE_URL=...`)
**Content-Type:** `application/json; charset=utf-8`
**Authorization:** Tüm uçlar `Authorization: Bearer <accessToken>` ister.

> Subscription **sadece OWNER** seviyesinde. Manager hesapları owner'ın aboneliğinden faydalanır; manager'a subscription endpoint'leri **403 FORBIDDEN** döner.

---

## Global headers (her istekte)

| Header            | Source                          | Example             | Required |
| ----------------- | ------------------------------- | ------------------- | -------- |
| `Accept-Language` | Cihaz dili (`en` / `ru` / `ky`) | `ru`                | Optional |
| `versionBuild`    | App build number                | `42`                | Optional |
| `os`              | Platform                        | `ios` / `android`   | Optional |
| `Authorization`   | `Bearer <accessToken>`          | `Bearer eyJhbGc...` | Required |

---

## Authorization Roller

| Endpoint                                                | OWNER | MANAGER |
| ------------------------------------------------------- | :---: | :-----: |
| GET `/api/v1/subscription`                              |  ✅   |   ❌    |
| GET `/api/v1/subscription/pricing`                      |  ✅   |   ❌    |
| POST `/api/v1/subscription/checkout`                    |  ✅   |   ❌    |
| GET `/api/v1/subscription/payment/{id}`                 |  ✅   |   ❌    |
| POST `/api/v1/subscription/payment/{id}/confirm` (mock) |  ✅   |   ❌    |

> Manager bu uçlara `403 FORBIDDEN` alır. Manager'ın görmesi gereken bilgi (örn: owner'ı EXPIRED ise blocked-screen) **dolaylı** olarak diğer endpoint'lerin `403 SUBSCRIPTION_REQUIRED` cevabı üzerinden anlaşılır (bkz. § Subscription gate aşağıda).

---

## Subscription gate — diğer endpoint'lere etkisi

Owner'ın subscription'ı `EXPIRED` ya da `GRACE` ile `graceDaysRemaining = 0` ise, **yazma** endpoint'leri (session start/pause/resume/finish, venue/table create/update/delete, manager invite) `403 SUBSCRIPTION_REQUIRED` döner. Read endpoint'leri (list / get) etkilenmez.

```json
{
  "code": "SUBSCRIPTION_REQUIRED",
  "message": {
    "en": "Subscription expired. Renew to continue using core features.",
    "ru": "Подписка истекла. Продлите её, чтобы продолжить пользоваться основными функциями.",
    "ky": "Жазылуу бүттү. Негизги функцияларды колдонуу үчүн узартыңыз."
  },
  "details": null
}
```

| Endpoint kategorisi                     | EXPIRED owner için          |
| --------------------------------------- | --------------------------- |
| Session: start, pause, resume, finish   | 403 `SUBSCRIPTION_REQUIRED` |
| Venue/table: create, update, delete     | 403 `SUBSCRIPTION_REQUIRED` |
| Auth: invite-code (manager invite)      | 403 `SUBSCRIPTION_REQUIRED` |
| Read endpoint'ler (list/get/profile)    | normal işler                |
| Subscription endpoint'leri (bu doc)     | normal işler                |
| Logout, change-password, delete-account | normal işler                |

> Manager isteğinde subscription kontrolü **owner'ın subscription'ına** bakılır (manager'ın bağlı olduğu owner). Manager bağımsız bir abonesi olamayacağı için bu doğal davranıştır.

---

## Daily cron (status transition)

Backend günde bir kez (öneri: 00:00 UTC) tüm `ACTIVE / GRACE` subscription'ları tarar:

```
for each subscription S:
  if S.status == ACTIVE and S.endDate < now:
    S.status = GRACE
    S.gracePeriodEndsAt = S.endDate + config.gracePeriodDays   # default 5

  elif S.status == GRACE and S.gracePeriodEndsAt < now:
    S.status = EXPIRED
```

Mobile bu transition'ları izlemez — her `GET /subscription` çağrısında güncel state'i okur. Backend cron çalışmadan da (örn. öğle 12:00'de istek geldiğinde) endpoint **anlık recompute** edip doğru status'u dönmelidir; cron sadece persist eder.

---

## Standart Error Yapısı

`auth-api.md` / `home_page_api.md` ile aynı zarf:

```json
{
  "code": "SUBSCRIPTION_REQUIRED",
  "message": {
    "en": "...",
    "ru": "...",
    "ky": "..."
  },
  "details": null
}
```

### Subscription-spesifik Error Code'ları

| Code                        | HTTP | Anlamı                                                    |
| --------------------------- | :--: | --------------------------------------------------------- |
| `SUBSCRIPTION_REQUIRED`     | 403  | Owner'ın aboneliği yok / GRACE@0 / EXPIRED                |
| `NO_TABLES`                 | 422  | Owner'ın hiç masası yok, ödeme hesaplanamaz               |
| `INVALID_DURATION`          | 422  | `months` aralık dışı (`< minDurationMonths` veya `>` max) |
| `PAYMENT_NOT_FOUND`         | 404  | Verilen `paymentId` ile kayıt yok                         |
| `PAYMENT_ALREADY_PROCESSED` | 409  | `PAID`/`FAILED` payment'ı tekrar confirm edilemez         |
| `PAYMENT_PROVIDER_ERROR`    | 502  | 3rd-party (Finik) tarafında hata                          |
| `PRICING_MISMATCH`          | 409  | Mobile'ın gönderdiği fiyat backend'inkiyle uyuşmuyor      |
| `FORBIDDEN`                 | 403  | Manager bu endpoint'i çağırdı                             |
| `VALIDATION_ERROR`          | 422  | Body validation                                           |

> Mobile `SUBSCRIPTION_REQUIRED`'ı global handler'da yakalar → kullanıcıya "Aboneliğin bitti" dialog'u + Continue butonu sunar.

---

## Domain Modelleri

### Subscription

Owner'ın aktif (veya en son) abonelik kaydı.

```ts
{
  id: string (uuid),
  ownerId: string (uuid),
  status: "ACTIVE" | "GRACE" | "EXPIRED",
  source: "TRIAL" | "PAID",
  startDate: string (ISO 8601),    // bu periyodun başlangıcı
  endDate: string (ISO 8601),      // bu periyodun bitişi (grace dahil DEĞİL)
  gracePeriodEndsAt: string (ISO 8601) | null, // GRACE/EXPIRED'da dolu, ACTIVE'de null
  daysUntilExpiry: integer,        // floor((endDate - now) / 24h), 0'a clamp; ACTIVE/GRACE/EXPIRED için anlamlı
  graceDaysRemaining: integer,     // GRACE'de aktif (0..gracePeriodDays), diğer durumda 0
  createdAt: string (ISO 8601),
  updatedAt: string (ISO 8601)
}
```

| Field                | Type                           | Notes                                                                                                                                                  |
| -------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `id`                 | uuid                           | Subscription kaydı id'si (her renewal yeni id üretmez — aynı kayıt güncellenir; alternatif: history için yeni kayıt + `previousId`. MVP'de tek kayıt). |
| `ownerId`            | uuid                           | Sahip                                                                                                                                                  |
| `status`             | `ACTIVE` / `GRACE` / `EXPIRED` | UPPERCASE                                                                                                                                              |
| `source`             | `TRIAL` / `PAID`               | İlk kayıt TRIAL, ilk ödemeden sonra PAID (rename edilir)                                                                                               |
| `startDate`          | ISO 8601                       | Mevcut periyot başlangıcı (UTC önerilir)                                                                                                               |
| `endDate`            | ISO 8601                       | Mevcut periyot bitişi (UTC). Grace **dahil değil**                                                                                                     |
| `gracePeriodEndsAt`  | ISO 8601 \| null               | `endDate + gracePeriodDays`. ACTIVE'de null tutmak öneri                                                                                               |
| `daysUntilExpiry`    | int                            | Mobile'ın UI threshold'larında kullandığı hazır sayı                                                                                                   |
| `graceDaysRemaining` | int                            | GRACE'de [0, gracePeriodDays]; ACTIVE/EXPIRED'da `0`                                                                                                   |

> **Status hesabı için tek doğruluk kaynağı `endDate` ve `gracePeriodEndsAt`'tir.** `daysUntilExpiry` / `graceDaysRemaining` mobile için convenience field'ları — backend her response'da anlık hesaplayıp gönderir. Mobile bu sayılara güvenir, kendisi hesap yapmaz (server time vs client time tutarsızlığı yaşamamak için).

### SubscriptionPricing

Mobile checkout ekranında hesaplama yapmadan önce çeker.

```ts
{
  pricePerTable: integer,          // tek bir masanın aylık fiyatı (config; tam sayı, kuruş yok)
  currency: "KGS" | "USD" | "RUB" | "KZT" | "TRY",
  tableCount: integer,             // owner'ın anlık toplam masa sayısı
  monthlyAmount: integer,          // pricePerTable × tableCount (convenience)
  minDurationMonths: integer,      // genelde 1
  maxDurationMonths: integer,      // genelde 12
  gracePeriodDays: integer,        // genelde 5
  freeTrialDays: integer,          // yeni hesaplar için (mobile bilgi amaçlı)
  expiryWarningDays: integer       // genelde 3 — mobile bu eşik altında "warning UI" çizer
}
```

| Field               | Notes                                                              |
| ------------------- | ------------------------------------------------------------------ |
| `pricePerTable`     | Backend config'den. Mobile **cache yapmaz**, her checkout'ta fetch |
| `tableCount`        | Soft-delete edilmemiş, owner'a bağlı tüm mekanların masa toplamı   |
| `monthlyAmount`     | `pricePerTable × tableCount`. `tableCount = 0` ise `0`             |
| `expiryWarningDays` | UI threshold'u; backend zorlamaz, sadece bilgi olarak döner        |

> Mobile'ın gönderdiği fiyatla backend'in beklediği fiyat farklıysa (örn: kullanıcı checkout açıktayken backend `pricePerTable`'ı güncelledi) `POST /checkout` `409 PRICING_MISMATCH` döner — mobile pricing'i refresh eder.

### Payment

Bir ödeme kaydı. Subscription history'sini bunlar oluşturur.

```ts
{
  id: string (uuid),
  subscriptionId: string (uuid),
  amount: integer,                 // ödenen toplam (snapshot)
  currency: "KGS" | "USD" | "RUB" | "KZT" | "TRY",
  months: integer,                 // satın alınan ay sayısı (>=1)
  tableCountSnapshot: integer,     // ödeme anındaki masa sayısı
  pricePerTableSnapshot: integer,  // ödeme anındaki birim fiyat
  status: "PENDING" | "PAID" | "FAILED",
  paymentUrl: string | null,       // 3rd-party redirect; mock-mode'da null
  provider: "MOCK" | "FINIK",      // hangi sağlayıcı kullanıldı
  providerPaymentId: string | null,// Finik'in dönüşü; mock'ta null
  createdAt: string (ISO 8601),
  paidAt: string (ISO 8601) | null,
  failedAt: string (ISO 8601) | null,
  failureReason: string | null
}
```

> **Snapshot kuralı:** Payment kaydı oluştuğu anda `pricePerTableSnapshot` ve `tableCountSnapshot` kopyalanır. Backend daha sonra fiyatı değiştirse veya owner masa eklese/silse, mevcut Payment etkilenmez. (Session `tarifAmountSnapshot` ile aynı felsefe.)

### SubscriptionDetailResponse

`GET /subscription` endpoint'inin döndürdüğü bütünleşik yapı.

```ts
{
  subscription: Subscription,
  payments: Payment[]   // tarih azalan; en yeni başta
}
```

---

## Endpoints

### 1. Get Subscription

Subscription detay ekranı açıldığında çağrılır. Mevcut abonelik özeti + ödeme geçmişi.

**Endpoint:**

```
GET /api/v1/subscription
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Response (200):**

```json
{
  "subscription": {
    "id": "aa0e8400-e29b-41d4-a716-446655440010",
    "ownerId": "user-001",
    "status": "ACTIVE",
    "source": "PAID",
    "startDate": "2026-04-15T10:30:00.000Z",
    "endDate": "2026-05-15T10:30:00.000Z",
    "gracePeriodEndsAt": null,
    "daysUntilExpiry": 15,
    "graceDaysRemaining": 0,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-04-15T10:35:00.000Z"
  },
  "payments": [
    {
      "id": "bb0e8400-e29b-41d4-a716-446655440020",
      "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
      "amount": 2000,
      "currency": "KGS",
      "months": 1,
      "tableCountSnapshot": 10,
      "pricePerTableSnapshot": 200,
      "status": "PAID",
      "paymentUrl": null,
      "provider": "MOCK",
      "providerPaymentId": null,
      "createdAt": "2026-04-15T10:30:00.000Z",
      "paidAt": "2026-04-15T10:30:30.000Z",
      "failedAt": null,
      "failureReason": null
    },
    {
      "id": "bb0e8400-e29b-41d4-a716-446655440019",
      "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
      "amount": 2000,
      "currency": "KGS",
      "months": 1,
      "tableCountSnapshot": 10,
      "pricePerTableSnapshot": 200,
      "status": "PAID",
      "paymentUrl": null,
      "provider": "MOCK",
      "providerPaymentId": null,
      "createdAt": "2026-03-15T10:30:00.000Z",
      "paidAt": "2026-03-15T10:30:14.000Z",
      "failedAt": null,
      "failureReason": null
    }
  ]
}
```

**TRIAL durumunda örnek:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "ACTIVE",
    "source": "TRIAL",
    "startDate": "2026-04-30T08:00:00.000Z",
    "endDate": "2026-05-14T08:00:00.000Z",
    "gracePeriodEndsAt": null,
    "daysUntilExpiry": 14,
    "graceDaysRemaining": 0,
    "createdAt": "2026-04-30T08:00:00.000Z",
    "updatedAt": "2026-04-30T08:00:00.000Z"
  },
  "payments": []
}
```

**GRACE durumunda örnek:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "GRACE",
    "source": "PAID",
    "startDate": "2026-03-15T10:30:00.000Z",
    "endDate": "2026-04-29T10:30:00.000Z",
    "gracePeriodEndsAt": "2026-05-04T10:30:00.000Z",
    "daysUntilExpiry": 0,
    "graceDaysRemaining": 4,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-04-30T00:00:00.000Z"
  },
  "payments": [ ... ]
}
```

**EXPIRED durumunda örnek:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "EXPIRED",
    "source": "PAID",
    "startDate": "2026-03-15T10:30:00.000Z",
    "endDate": "2026-04-29T10:30:00.000Z",
    "gracePeriodEndsAt": "2026-05-04T10:30:00.000Z",
    "daysUntilExpiry": 0,
    "graceDaysRemaining": 0,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-05-05T00:00:00.000Z"
  },
  "payments": [ ... ]
}
```

**Errors:**

| HTTP | `code`      | Trigger                                                                                                                                                                   |
| ---- | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 401  | —           | Token yok / expired                                                                                                                                                       |
| 403  | `FORBIDDEN` | Kullanıcı manager                                                                                                                                                         |
| 404  | —           | Owner'ın hiç subscription kaydı yok (sadece register sonrası TRIAL henüz oluşturulmadıysa — beklenmeyen durum). Mobile bunu "TRIAL henüz başlamamış" gibi handle edebilir |

---

### 2. Get Subscription Pricing

Checkout ekranı açıldığında çağrılır. Mobile **cache yapmaz**.

**Endpoint:**

```
GET /api/v1/subscription/pricing
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Response (200):**

```json
{
  "pricePerTable": 200,
  "currency": "KGS",
  "tableCount": 10,
  "monthlyAmount": 2000,
  "minDurationMonths": 1,
  "maxDurationMonths": 12,
  "gracePeriodDays": 5,
  "freeTrialDays": 14,
  "expiryWarningDays": 3
}
```

**`tableCount = 0` durumu:**

```json
{
  "pricePerTable": 200,
  "currency": "KGS",
  "tableCount": 0,
  "monthlyAmount": 0,
  "minDurationMonths": 1,
  "maxDurationMonths": 12,
  "gracePeriodDays": 5,
  "freeTrialDays": 14,
  "expiryWarningDays": 3
}
```

> Mobile bu durumda checkout'u açmaz, "Add a table to subscribe" empty state gösterir.

**Errors:**

| HTTP | `code`      | Trigger             |
| ---- | ----------- | ------------------- |
| 401  | —           | Token yok / expired |
| 403  | `FORBIDDEN` | Kullanıcı manager   |

---

### 3. Create Checkout (start payment)

Kullanıcı checkout ekranında "Pay" butonuna basınca çağrılır. Backend bir `Payment` kaydı oluşturur (`status: PENDING`), 3rd-party redirect URL döner (real mode) veya `null` döner (mock mode).

**Endpoint:**

```
POST /api/v1/subscription/checkout
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Body:**

```json
{
  "months": 3
}
```

| Field    | Type    | Required | Rules                                          |
| -------- | ------- | :------: | ---------------------------------------------- |
| `months` | integer |    ✅    | `>= minDurationMonths`, `<= maxDurationMonths` |

> **Body'ye fiyat veya tableCount EKLENMEZ.** Backend bunları kendi hesaplar (anti-tampering). Mobile sadece kaç ay istediğini söyler.

**Backend mantığı:**

1. Owner'ın `tableCount`'unu çek (anlık).
2. `tableCount == 0` → `422 NO_TABLES`.
3. `pricePerTable`'ı config'den al.
4. `amount = pricePerTable × tableCount × months`.
5. `Payment { status: PENDING, snapshot... }` oluştur.
6. Real mode'da 3rd-party'ye redirect URL iste, mock mode'da `paymentUrl: null` ile dön.

**Response (201):**

```json
{
  "id": "bb0e8400-e29b-41d4-a716-446655440021",
  "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "PENDING",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": null,
  "failureReason": null
}
```

**Real mode (Finik) örnek:**

```json
{
  "id": "bb0e8400-...",
  "subscriptionId": "aa0e8400-...",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "PENDING",
  "paymentUrl": "https://pay.finik.kg/...?token=abc123",
  "provider": "FINIK",
  "providerPaymentId": "FINIK-9988",
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": null,
  "failureReason": null
}
```

**Errors:**

| HTTP | `code`                   | Trigger                                           |
| ---- | ------------------------ | ------------------------------------------------- |
| 422  | `VALIDATION_ERROR`       | `months` integer değil                            |
| 422  | `INVALID_DURATION`       | `months < min` veya `months > max`                |
| 422  | `NO_TABLES`              | Owner'ın hiç masası yok                           |
| 502  | `PAYMENT_PROVIDER_ERROR` | Finik kullanıcısı oluşturulamadı / network hatası |
| 401  | —                        | Token yok / expired                               |
| 403  | `FORBIDDEN`              | Kullanıcı manager                                 |

> `SUBSCRIPTION_REQUIRED` bu endpoint'te **dönmez** — EXPIRED owner zaten ödeme yapmaya geliyor.

---

### 4. Get Payment Status (polling)

Mobile, ödeme akışında 3rd-party redirect'ten döndükten sonra bu endpoint'i pollar (5sn interval, max 60sn). Webhook server-side processing zaten bitirmiş olur; mobile sadece "PAID mi?" diye okur.

**Endpoint:**

```
GET /api/v1/subscription/payment/{id}
```

**Path Params:**

- `id` (uuid) — payment ID

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Response (200):**

```json
{
  "id": "bb0e8400-...",
  "subscriptionId": "aa0e8400-...",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "PAID",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": "2026-04-30T12:01:30.000Z",
  "failedAt": null,
  "failureReason": null
}
```

**Status değerleri:**

- `PENDING` — henüz ödeme tamamlanmadı (mobile pollamaya devam eder).
- `PAID` — ödeme onaylandı, subscription **uzatıldı**. Mobile success ekranını gösterir.
- `FAILED` — ödeme reddedildi. `failureReason` doldurulmuş olur ("Insufficient funds", "Card declined", vb.).

**Errors:**

| HTTP | `code`              | Trigger                                |
| ---- | ------------------- | -------------------------------------- |
| 404  | `PAYMENT_NOT_FOUND` | Bu id ile payment yok / başka owner'ın |
| 401  | —                   | Token yok / expired                    |
| 403  | `FORBIDDEN`         | Kullanıcı manager                      |

---

### 5. Confirm Payment (mock-only)

**Sadece MVP / mock mode için.** Gerçek Finik entegrasyonunda webhook bu işi yapacak; bu endpoint kaldırılacak veya disable edilecek (`config.paymentProvider == "MOCK"` iken aktif).

Mobile mock ödeme ekranında "Simulate success" / "Simulate failure" butonuna basınca çağrılır.

**Endpoint:**

```
POST /api/v1/subscription/payment/{id}/confirm
```

**Path Params:**

- `id` (uuid) — payment ID

**Body:**

```json
{
  "outcome": "PAID"
}
```

| Field     | Type | Required | Rules              |
| --------- | ---- | :------: | ------------------ |
| `outcome` | enum |    ✅    | `PAID` \| `FAILED` |

**Backend mantığı (`outcome = PAID`):**

1. Payment'ı `PAID` olarak işaretle, `paidAt = now`.
2. Subscription'ı uzat:
   - Eğer mevcut `endDate > now` (early renew): `newEndDate = oldEndDate + months × 30 days`.
   - Aksi halde (GRACE / EXPIRED): `newEndDate = now + months × 30 days`.
3. `status = ACTIVE`, `source = PAID`, `gracePeriodEndsAt = null`, `startDate = newPeriodStart` (= now veya oldEndDate).
4. Güncellenmiş `Payment` döner.

**Response (200) — outcome=PAID:**

```json
{
  "id": "bb0e8400-...",
  "subscriptionId": "aa0e8400-...",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "PAID",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": "2026-04-30T12:01:30.000Z",
  "failedAt": null,
  "failureReason": null
}
```

**Response (200) — outcome=FAILED:**

```json
{
  "id": "bb0e8400-...",
  "subscriptionId": "aa0e8400-...",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "FAILED",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": "2026-04-30T12:01:30.000Z",
  "failureReason": "Simulated failure"
}
```

**Errors:**

| HTTP | `code`                      | Trigger                                          |
| ---- | --------------------------- | ------------------------------------------------ |
| 404  | `PAYMENT_NOT_FOUND`         | Bu id ile payment yok / başka owner'ın           |
| 409  | `PAYMENT_ALREADY_PROCESSED` | Payment `PENDING` değil (zaten PAID veya FAILED) |
| 422  | `VALIDATION_ERROR`          | `outcome` enum dışı                              |
| 403  | `FORBIDDEN`                 | Kullanıcı manager                                |
| 404  | —                           | Real mode'da bu endpoint disabled (404 mantıklı) |

> Bu endpoint **production'da disabled** olmalı. Mobile production build'de mock ödeme akışını çağırmaz; Finik real flow'a girer ve webhook backend'i tetikler.

---

## Profile entegrasyonu (özet)

Profile endpoint'i (mobile'da `AuthRepository.fetchProfile()`, backend tarafında `GET /profile` veya `GET /me` — bu task'in kapsamı dışı, ayrı doc) zaten `subscriptionEndDate` field'ı içeriyor (bkz. `packages/auth/lib/models/profile_model.dart`). Subscription rollout'unda profile response'una **subscription özeti** eklenir:

```json
{
  "user": { ... },
  "venuesCount": 2,
  "managersCount": 3,
  "subscription": {
    "status": "ACTIVE",
    "endDate": "2026-05-15T10:30:00.000Z",
    "daysUntilExpiry": 15,
    "graceDaysRemaining": 0
  }
}
```

> Eski `subscriptionEndDate` field'ı **deprecated** edilebilir; yeni `subscription` objesi ile değiştirilir. Mobile tek source of truth okur. Geri uyumluluk için backend bir süre her ikisini birden doldurabilir.

Bu sayede profile fetch'te ekstra round-trip olmadan profile widget'ı doğru subtitle'ı çizer ("Active · until X" / "Expires in N days" / "Grace · N days left" / "Expired").

---

## Endpoint Özeti

| Method | Path                                        | Auth  | Rate Limit |
| ------ | ------------------------------------------- | ----- | ---------- |
| GET    | `/api/v1/subscription`                      | Owner | 30/dakika  |
| GET    | `/api/v1/subscription/pricing`              | Owner | 30/dakika  |
| POST   | `/api/v1/subscription/checkout`             | Owner | 5/dakika   |
| GET    | `/api/v1/subscription/payment/{id}`         | Owner | 60/dakika  |
| POST   | `/api/v1/subscription/payment/{id}/confirm` | Owner | 10/dakika  |

---

## Yan etkiler — diğer endpoint'lere eklenmesi gerekenler

Subscription rollout aşağıdaki endpoint'leri etkiler — mobile bekliyor olacak:

1. **Tüm yazma endpoint'leri** (session start/pause/resume/finish, venue/table create/update/delete, manager invite) → owner subscription'ı `EXPIRED` veya `GRACE@0` ise `403 SUBSCRIPTION_REQUIRED` dönmeli (bkz. § Subscription gate).
2. **Register endpoint** (`POST /auth/register`, role=OWNER) → başarılı kayıttan **hemen sonra** backend `Subscription { status: ACTIVE, source: TRIAL, startDate: now, endDate: now + freeTrialDays }` oluşturmalı.
3. **Profile endpoint** → response'a `subscription: { status, endDate, daysUntilExpiry, graceDaysRemaining }` eklenmeli.

---

## Açık sorular / Karar verilecekler

- [ ] **Subscription history** — aynı kayıt update'lenecek mi (MVP), yoksa her renewal yeni `Subscription` record'u açılacak mı (audit için temiz)? Mobile UI tek "current" gösterip Payment'lardan history çıkarıyor — şu an MVP kararı **tek kayıt update**, ama backend tarafı tercih ederse her renewal yeni kayıt da çalışır (mobile etkilenmez).
- [ ] **30-day month vs takvim ayı** — flow doc § 11'de soruldu. API kontratı bu kararı bilmek zorunda değil ama backend implementasyonunu etkiler.
- [ ] **TRIAL bittikten sonra grace** — flow doc'a göre TRIAL'da da 5 gün grace olacak mı? **Öneri:** evet, kuralı tek tutmak için (cron'da `source` ayrımı yok).
- [ ] **Webhook URL formatı** — Finik specifik; Finik dokümantasyonu geldiğinde bu doc'a `POST /webhooks/finik` eklenecek.
- [ ] **Refund endpoint** — MVP scope dışı; v2'de `POST /subscription/payment/{id}/refund` eklenebilir.
- [ ] **Currency multi-region** — MVP'de KGS sabit; backend yine de `currency` field'ı dönüyor (future-proof).
- [ ] **Pricing config UI** — `pricePerTable` admin paneli üzerinden mi değişecek, env variable mı? Backend implementasyon detayı.
- [ ] **Pre-check endpoint** — checkout açılırken `POST /subscription/checkout/preview { months }` ile total'i sunucudan doğrulattırmak istiyor muyuz? **Öneri:** hayır, mobile pricing × months hesabı + `409 PRICING_MISMATCH` yeterli koruma.
