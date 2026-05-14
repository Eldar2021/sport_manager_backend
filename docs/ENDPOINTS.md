# API Endpoints — Mobile QA Sözleşmesi

**Hedef kitle:** Mobil tester. Tüm 35 endpoint, request/response şekilleri, kullanılan hata kodları, gerekli header'lar tek dokümanda.

**Base URL:** `http://localhost:8080` (local) — production'da `--dart-define=BASE_URL=...`  
**Content-Type:** `application/json; charset=utf-8`  
**Swagger UI:** `<BASE_URL>/swagger-ui.html`

---

## Global Header'lar

| Header            | Açıklama                                                     | Örnek             | Zorunlu                         |
| ----------------- | ------------------------------------------------------------ | ----------------- | ------------------------------- |
| `Authorization`   | `Bearer <accessToken>`                                       | `Bearer eyJ...`   | Public olmayan tüm endpoint'ler |
| `Accept-Language` | `en` / `ru` / `ky` (hata metinleri 3 dilde döner; opsiyonel) | `ru`              | Hayır                           |
| `os`              | Platform — sadece log/MDC için                               | `ios` / `android` | Hayır                           |
| `versionBuild`    | Mobil build numarası — log/analytics                         | `42`              | Hayır                           |
| `Content-Type`    | `application/json`                                           | —                 | Body olan endpoint'lerde        |

**Public endpoint'ler** (`Authorization` istemez):

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/forgot-password`
- `GET /actuator/health` (ops)
- `GET /swagger-ui.html`, `/v3/api-docs/**` (docs)

---

## Standart Hata Zarfı

Tüm hata yanıtları aynı yapıda:

```json
{
  "code": "ERROR_CODE",
  "message": {
    "en": "...",
    "ru": "...",
    "ky": "..."
  },
  "details": null
}
```

Validation hatasında `details` array dolar:

```json
{
  "code": "VALIDATION_ERROR",
  "message": { "en": "Validation failed", "ru": "...", "ky": "..." },
  "details": [
    {
      "field": "email",
      "rule": "Email",
      "message": "must be a well-formed email"
    },
    {
      "field": "password",
      "rule": "Size",
      "message": "size must be between 8 and 100"
    }
  ]
}
```

---

## Subscription Gate

Owner aboneliği `EXPIRED` veya `GRACE@0` ise, **tüm yazma endpoint'leri** `403 SUBSCRIPTION_REQUIRED` döner. Etkilenenler:

- Session: `start`, `pause`, `resume`, `finish`, `cancel`
- Venue: `create`, `update`, `delete`, `selected` (PATCH)
- Table: `create`, `update`, `delete`
- Auth: `invite-code`
- Manager: `delete`

Okuma endpoint'leri (list/get) ve `subscription/*` endpoint'leri etkilenmez (owner yenileyebilsin).

Yeni OWNER kaydı → otomatik 14 günlük TRIAL oluşur.

---

# 1. Auth — `/api/v1/auth/**`

## 1.1 POST `/api/v1/auth/login` — Public

**Request:**

```json
{
  "username": "test@example.com",
  "password": "Test1234"
}
```

`username` email veya phone olabilir.

**Response 200:**

```json
{
  "user": {
    "id": "550e8400-...",
    "name": "John Doe",
    "role": "OWNER",
    "email": "test@example.com",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

**Errors:**
| HTTP | code |
|------|------|
| 401 | `INVALID_CREDENTIALS` |
| 423 | `ACCOUNT_LOCKED` |
| 422 | `VALIDATION_ERROR` |

---

## 1.2 POST `/api/v1/auth/register` — Public

**OWNER kaydı:**

```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "+996 700 000 003",
  "password": "Test1234",
  "role": "OWNER"
}
```

**MANAGER kaydı** (`inviteCode` zorunlu):

```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+996 700 000 004",
  "password": "Test1234",
  "role": "MANAGER",
  "inviteCode": "INVITE-A1B2C3D4"
}
```

**Response 200:** Login ile aynı yapı (`{user, accessToken, refreshToken}`).

> **OWNER kaydında** backend arka planda 14 günlük TRIAL aboneliği oluşturur.  
> **MANAGER kaydında** invite-code'un sahibini owner olarak atar.

**Errors:**
| HTTP | code |
|------|------|
| 409 | `EMAIL_ALREADY_USED` |
| 409 | `PHONE_ALREADY_USED` |
| 400 | `INVALID_INVITE_CODE` |
| 422 | `VALIDATION_ERROR` |

---

## 1.3 POST `/api/v1/auth/refresh` — Public

**Request:**

```json
{ "refreshToken": "eyJhbGc..." }
```

**Response 200** (user **YOK**, sadece tokenlar):

```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

Her refresh çağrısı yeni refresh token üretir (rotation). Eski refresh token bir daha geçersizdir.

**Errors:**
| HTTP | code |
|------|------|
| 401 | `SESSION_EXPIRED` |

---

## 1.4 POST `/api/v1/auth/logout` — Authenticated

**Request:** Body yok.  
**Response:** `200 OK` (boş)

Refresh token sıfırlanır. Access token TTL sonuna kadar geçerli kalır.

---

## 1.5 POST `/api/v1/auth/forgot-password` — Public

**Request:**

```json
{ "email": "user@example.com" }
```

**Response:** `503 SERVICE_UNAVAILABLE`

> MVP'de gerçek email gönderimi yok. Phase 5+ Spring Mail entegrasyonu ile aktifleşecek.

---

## 1.6 POST `/api/v1/auth/invite-code` — OWNER, Subscription Gate

**Request:** Body yok.

**Response 200:**

```json
{
  "code": "INVITE-A1B2C3D4",
  "expiresAt": "2026-05-21T12:34:56.789"
}
```

**Errors:**
| HTTP | code |
|------|------|
| 403 | `FORBIDDEN` (OWNER değilse) |
| 403 | `SUBSCRIPTION_REQUIRED` |

---

# 2. Home — Venue + Table — `/api/v1/**`

## 2.1 GET `/api/v1/venue/list` — Both roles

**Response 200:**

```json
[
  {
    "id": "550e8400-...",
    "name": "Merkez Şube",
    "number": 1,
    "address": "Chui Avenue 132, Bishkek",
    "selected": true,
    "tableCount": 3,
    "createdAt": "2026-04-15T10:30:00Z",
    "updatedAt": "2026-04-20T14:22:00Z"
  }
]
```

MANAGER için: bağlı olduğu OWNER'ın venue'lerini görür.  
Boş → `[]`. Sıralama: `number ASC, createdAt ASC`.

---

## 2.2 GET `/api/v1/venue/selected` — Both roles

**Response 200:**

```json
{
  "venue": { ... }, // VenueResponse
  "tables": [
    {
      "id": "660e8400-...",
      "venueId": "550e8400-...",
      "name": "VIP Salon",
      "number": 1,
      "description": "8-ball pool table",
      "tarifAmount": 250,
      "currency": "KGS",
      "tarifType": "HOUR",
      "session": {
        "id": "770e8400-...",
        "tableId": "660e8400-...",
        "managerId": "user-101",
        "isActive": true,
        "isPaused": false,
        "startedAt": "2026-04-27T18:42:00Z",
        "pausedAt": null,
        "resumedAt": null,
        "totalPausedSeconds": 0,
        "tarifAmountSnapshot": 250,
        "tarifTypeSnapshot": "HOUR"
      },
      "createdAt": "...",
      "updatedAt": "..."
    }
  ]
}
```

Hiç venue yoksa: `404 VENUE_NOT_FOUND`. İlk login sonrası MANAGER aynı hatayı alırsa owner'ın hiç venue'si yok demektir.

---

## 2.3 PATCH `/api/v1/venue/selected` — Both roles, Subscription Gate

**Request:**

```json
{ "venueId": "550e8400-..." }
```

**Response 200:** `GET /venue/selected` ile aynı yapıda — yeni seçili venue + masaları.

**Errors:** `404 VENUE_NOT_FOUND`, `403 SUBSCRIPTION_REQUIRED`.

---

## 2.4 POST `/api/v1/venue/create` — OWNER, Subscription Gate

**Request:**

```json
{
  "name": "Merkez Şube",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek"
}
```

| Field     | Kural                     |
| --------- | ------------------------- |
| `name`    | 1-100 char, NotBlank      |
| `number`  | >= 1, owner içinde unique |
| `address` | 0-255 char, nullable      |

**Response 201:** `VenueResponse` (yukarıdaki gibi).

İlk venue otomatik `selected: true` olur.

**Errors:**
| HTTP | code |
|------|------|
| 422 | `VALIDATION_ERROR` |
| 409 | `VENUE_NUMBER_TAKEN` |
| 403 | `FORBIDDEN` (MANAGER) |
| 403 | `SUBSCRIPTION_REQUIRED` |

---

## 2.5 PUT `/api/v1/venue/{id}` — OWNER, Subscription Gate

**Path:** `id` (UUID)  
**Request:** Same as create.

**Response 200:** `VenueResponse`.

**Errors:** `404 VENUE_NOT_FOUND`, `409 VENUE_NUMBER_TAKEN`, `422 VALIDATION_ERROR`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

## 2.6 DELETE `/api/v1/venue/{id}` — OWNER, Subscription Gate

**Response 200:**

```json
{ "id": "550e8400-...", "deleted": true }
```

Soft-delete. Masaları cascade soft-delete edilir. Aktif session varsa `409 TABLE_HAS_ACTIVE_SESSION`. Selected silinince en eski venue otomatik selected olur.

---

## 2.7 POST `/api/v1/table/create` — OWNER, Subscription Gate

**Request:**

```json
{
  "venueId": "550e8400-...",
  "name": "VIP Salon",
  "number": 1,
  "description": "8-ball pool table",
  "tarifAmount": 250,
  "currency": "KGS",
  "tarifType": "HOUR"
}
```

| Field         | Kural                                 |
| ------------- | ------------------------------------- |
| `venueId`     | Owner'a ait venue                     |
| `name`        | 0-100 char, nullable                  |
| `number`      | >= 1, venue içinde unique             |
| `description` | 0-500 char, nullable                  |
| `tarifAmount` | 1-1.000.000 (integer, kuruş yok)      |
| `currency`    | `KGS` / `USD` / `RUB` / `KZT` / `TRY` |
| `tarifType`   | `MINUTE` / `HOUR` / `DAY`             |

**Response 201:** `TableResponse` (`session` = null).

**Errors:** `404 VENUE_NOT_FOUND`, `409 TABLE_NUMBER_TAKEN`, `422 VALIDATION_ERROR`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

## 2.8 PUT `/api/v1/table/{id}` — OWNER, Subscription Gate

**Request:** Create ile aynı (venueId yok sayılır; masa başka venue'ya taşınamaz).

**Response 200:** `TableResponse`. Mevcut session varsa session field doludur.

> Aktif session sırasında `tarifAmount`/`tarifType` değişse bile o session etkilenmez (snapshot kuralı).

**Errors:** `404 TABLE_NOT_FOUND`, `409 TABLE_NUMBER_TAKEN`, `422 VALIDATION_ERROR`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

## 2.9 DELETE `/api/v1/table/{id}` — OWNER, Subscription Gate

**Response 200:**

```json
{ "id": "660e8400-...", "deleted": true }
```

Aktif session varsa `409 TABLE_HAS_ACTIVE_SESSION`.

---

# 3. Session — `/api/v1/session/**`

Tüm endpoint'ler: Both roles + Subscription Gate.  
**Manager izolasyonu:** Manager sadece kendi owner'ının masalarındaki session'lara erişebilir. Başka owner → 403.

## 3.1 POST `/api/v1/session/start`

**Request:**

```json
{ "tableId": "660e8400-..." }
```

> `startedAt` body'de YOK — backend yazar.

**Response 201 (SessionLite):**

```json
{
  "id": "770e8400-...",
  "tableId": "660e8400-...",
  "managerId": "user-101",
  "status": "ACTIVE",
  "startedAt": "2026-04-27T18:42:00Z",
  "totalPausedSeconds": 0,
  "pausedAt": null,
  "tarifAmountSnapshot": 250,
  "tarifTypeSnapshot": "HOUR"
}
```

**Errors:**
| HTTP | code |
|------|------|
| 404 | `TABLE_NOT_FOUND` |
| 409 | `TABLE_HAS_ACTIVE_SESSION` (DB-level partial unique index ile garanti) |
| 403 | `FORBIDDEN` (başka owner'ın masası) |
| 403 | `SUBSCRIPTION_REQUIRED` |

---

## 3.2 POST `/api/v1/session/{id}/pause`

**Path:** `id` (session UUID)  
**Body:** Boş

**Response 200 (SessionLite):** `status: "PAUSED"`, `pausedAt` dolu.

**Errors:** `404 SESSION_NOT_FOUND`, `409 SESSION_NOT_ACTIVE`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

## 3.3 POST `/api/v1/session/{id}/resume`

**Body:** Boş

**Response 200 (SessionLite):** `status: "ACTIVE"`, `totalPausedSeconds` artar, `pausedAt: null`.

**Errors:** `404 SESSION_NOT_FOUND`, `409 SESSION_NOT_PAUSED`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

## 3.4 POST `/api/v1/session/{id}/finish`

**Request (opsiyonel body):**

```json
{ "discountPercent": 10 }
```

`discountPercent` 0-100, default 0.

**Response 200 (SessionResult):**

```json
{
  "id": "770e8400-...",
  "tableId": "660e8400-...",
  "managerId": "user-101",
  "status": "COMPLETED",
  "startedAt": "2026-04-27T18:42:00Z",
  "endedAt": "2026-04-27T20:12:00Z",
  "durationSeconds": 4800,
  "subtotal": 333,
  "discountPercent": 10,
  "totalAmount": 300,
  "cancelReason": null
}
```

Hesap:

```
billableSeconds = (endedAt - startedAt) - totalPausedSeconds
units = billableSeconds / { MINUTE:60, HOUR:3600, DAY:86400 }
subtotal = round(units × tarifAmountSnapshot)
totalAmount = subtotal - round(subtotal × discountPercent / 100)
```

PAUSED session finish edilirse otomatik resume + finish yapılır.

**Errors:**
| HTTP | code |
|------|------|
| 404 | `SESSION_NOT_FOUND` |
| 409 | `SESSION_ALREADY_COMPLETED` |
| 422 | `INVALID_DISCOUNT` |
| 403 | `FORBIDDEN`, `SUBSCRIPTION_REQUIRED` |

---

## 3.5 POST `/api/v1/session/{id}/cancel`

**Request:**

```json
{ "reason": "Yanlış masaya tıkladım" }
```

`reason` 1-200 char, NotBlank.

**Response 200 (SessionResult):**

```json
{
  "id": "770e8400-...",
  "tableId": "660e8400-...",
  "managerId": "user-101",
  "status": "CANCELLED",
  "startedAt": "...",
  "endedAt": "...",
  "durationSeconds": null,
  "subtotal": null,
  "discountPercent": null,
  "totalAmount": null,
  "cancelReason": "Yanlış masaya tıkladım"
}
```

> **MANAGER sadece ilk 60 saniyede** cancel edebilir. Sonra `422 CANCEL_WINDOW_EXPIRED`. OWNER her zaman cancel edebilir.

**Errors:** `404 SESSION_NOT_FOUND`, `409 SESSION_ALREADY_COMPLETED`, `422 CANCEL_WINDOW_EXPIRED`, `403 FORBIDDEN`, `403 SUBSCRIPTION_REQUIRED`.

---

# 4. Managers — `/api/v1/managers/**`

OWNER-only.

## 4.1 GET `/api/v1/managers`

**Response 200:**

```json
[
  {
    "id": "user-101",
    "name": "Айбек Асанов",
    "username": "aibek",
    "lastSeenAt": "2026-04-30T08:15:00Z"
  },
  {
    "id": "user-103",
    "name": "Данияр",
    "username": "daniyar",
    "lastSeenAt": null
  }
]
```

Sıralama: `lastSeenAt DESC NULLS LAST, name ASC`. Soft-deleted manager'lar dışlanır.

`lastSeenAt` 5-dakika throttle ile güncellenir (her token request'i yazmaz).

**Errors:** `403 FORBIDDEN` (MANAGER).

---

## 4.2 DELETE `/api/v1/managers/{id}` — Subscription Gate

**Response:** `204 No Content`

Soft-delete + refresh token sıfırlama (forced logout). Aktif session varsa `409 HAS_ACTIVE_SESSION`.

**Errors:**
| HTTP | code |
|------|------|
| 404 | `MANAGER_NOT_FOUND` (başka owner'ınki de 404 — leak yok) |
| 409 | `HAS_ACTIVE_SESSION` |
| 403 | `FORBIDDEN`, `SUBSCRIPTION_REQUIRED` |

---

# 5. Reports — `/api/v1/reports/**`

OWNER-only. Manager `403 FORBIDDEN` alır.

## Ortak Query Parametreleri

| Param     | Tip                                            | Açıklama                             |
| --------- | ---------------------------------------------- | ------------------------------------ |
| `period`  | `TODAY` / `WEEK` / `MONTH` / `YEAR` / `CUSTOM` | Bucket boyutu seçimi                 |
| `from`    | ISO 8601 UTC                                   | `2026-05-01T00:00:00Z`               |
| `to`      | ISO 8601 UTC, exclusive                        | `2026-05-15T00:00:00Z`               |
| `venueId` | UUID                                           | Zorunlu (tek venue scope)            |
| `compare` | boolean, default `true`                        | `false` → previous block gönderilmez |

Bucket: `YEAR` → ay; diğerleri → gün.

---

## 5.1 GET `/api/v1/reports/venues`

Query parametresi YOK.

**Response 200:**

```json
[{ "id": "550e8400-...", "name": "Merkez Şube", "number": 1 }]
```

---

## 5.2 GET `/api/v1/reports/overview`

**Response 200:**

```json
{
  "totalRevenue": 142500,
  "totalSessions": 384,
  "cancelledSessions": 12,
  "currency": "KGS",
  "previous": {
    "totalRevenue": 131000,
    "totalSessions": 360,
    "cancelledSessions": 9,
    "currency": "KGS",
    "previous": null
  }
}
```

`compare=false` veya `period=TODAY` → `previous: null`.

`previous` = **clipped previous** (geçen periyodun ilk N günü, current ile aynı uzunluk).

---

## 5.3 GET `/api/v1/reports/revenue-series`

**Response 200:**

```json
[
  { "bucket": "2026-05-01T00:00:00Z", "revenue": 8400, "sessions": 22 },
  { "bucket": "2026-05-02T00:00:00Z", "revenue": 11200, "sessions": 28 }
]
```

Tüm bucket'lar dolu döner (boş günler `revenue=0, sessions=0`). YEAR'da `bucket` ayın 1'i.

---

## 5.4 GET `/api/v1/reports/tables`

**Response 200:**

```json
[
  {
    "tableId": "660e8400-...",
    "tableName": "VIP Salon",
    "tableNumber": 1,
    "venueId": "550e8400-...",
    "venueName": "Merkez Şube",
    "revenue": 45200,
    "sessions": 123,
    "currency": "KGS",
    "deltaPercent": 8
  }
]
```

Sıralama: `revenue DESC, tableNumber ASC`. `compare=false` veya yetersiz veri → `deltaPercent: null`.

---

## 5.5 GET `/api/v1/reports/tables/{id}`

**Path:** `id` (table UUID)

**Response 200:**

```json
{
  "summary": {
    "tableId": "660e8400-...",
    "tableName": "VIP Salon",
    "tableNumber": 1,
    "venueId": "550e8400-...",
    "venueName": "Merkez Şube",
    "revenue": 45200,
    "sessions": 123,
    "currency": "KGS",
    "deltaPercent": 8
  },
  "revenueSeries": [
    { "bucket": "2026-05-01T00:00:00Z", "revenue": 1800, "sessions": 6 }
  ],
  "hourHeatmap": [
    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1200, 2800, 3100, 4500, 5200, 6800, 7100, 8400, 9200, 7800, 5400, 2100, 0],
    [...], [...], [...], [...], [...], [...]
  ]
}
```

`hourHeatmap`: sabit `7 × 24` matris. Satırlar Pzt=0…Paz=6, sütunlar saat 0…23, hücre = o gün × saat dilimindeki COMPLETED session toplam revenue. UTC.

**Errors:** `404 TABLE_NOT_FOUND` (table başka venue'da bile dahil — venueId↔tableId tutarlılık kontrolü var).

---

## 5.6 GET `/api/v1/reports/managers`

**Response 200:**

```json
[
  {
    "managerId": "user-101",
    "name": "Айбек Асанов",
    "username": "aibek",
    "revenue": 78400,
    "sessions": 142,
    "cancelCount": 5,
    "currency": "KGS"
  }
]
```

Sıralama: `revenue DESC`. Sadece bu venue'de session başlatmış manager'lar.

---

## 5.7 GET `/api/v1/reports/managers/{id}`

**Path:** `id` (manager UUID — bu owner'a ait olmalı, aksi `404 MANAGER_NOT_FOUND`)

**Response 200:**

```json
{
  "summary": {
    "managerId": "user-101",
    "name": "Айбек Асанов",
    "username": "aibek",
    "revenue": 78400,
    "sessions": 142,
    "cancelCount": 5,
    "currency": "KGS"
  },
  "sessionLog": [
    {
      "sessionId": "770e8400-...",
      "tableId": "660e8400-...",
      "tableName": "VIP Salon",
      "tableNumber": 1,
      "venueName": "Merkez Şube",
      "startedAt": "2026-05-14T18:42:00Z",
      "endedAt": "2026-05-14T20:12:00Z",
      "status": "COMPLETED",
      "currency": "KGS",
      "durationSeconds": 4800,
      "totalAmount": 333,
      "cancelReason": null
    },
    {
      "sessionId": "770e8400-...-aborted",
      "tableId": "660e8400-...",
      "tableName": null,
      "tableNumber": 3,
      "venueName": "Merkez Şube",
      "startedAt": "...",
      "endedAt": "...",
      "status": "CANCELLED",
      "currency": "KGS",
      "durationSeconds": null,
      "totalAmount": null,
      "cancelReason": "Yanlış başladım"
    }
  ]
}
```

`sessionLog` max 40 satır, `startedAt DESC`.

**Errors:** `404 MANAGER_NOT_FOUND`.

---

## 5.8 GET `/api/v1/reports/forecast`

**Response 200:**

```json
{
  "points": [
    {
      "bucket": "2026-05-01T...",
      "expected": 8400,
      "lower": 8400,
      "upper": 8400,
      "isProjection": false
    },
    {
      "bucket": "2026-05-15T...",
      "expected": 9800,
      "lower": 8330,
      "upper": 11270,
      "isProjection": true
    }
  ],
  "projectedTotal": 165000,
  "previousPeriodTotal": 147000,
  "currency": "KGS"
}
```

`isProjection: false` = gerçek geçmiş gün; `true` = lineer regresyon tahmini. Confidence band ±15%.

`previousPeriodTotal` = **full** geçen takvim periyodu (clipped DEĞİL).

**Errors:**
| HTTP | code |
|------|------|
| 422 | `NOT_ENOUGH_DATA` (< 7 gün veya YEAR için < 2 ay) |

> Mobile `period=TODAY` durumunda bu endpoint'i **çağırmaz**.

---

# 6. Subscription — `/api/v1/subscription/**`

OWNER-only. MANAGER `403 FORBIDDEN` alır.  
**Şu an sadece MOCK mode aktif.** Finik gerçek entegrasyonu anlaşma sonrası açılacak.

## 6.1 GET `/api/v1/subscription`

**Response 200:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "ACTIVE",
    "source": "TRIAL",
    "startDate": "2026-04-30T08:00:00Z",
    "endDate": "2026-05-14T08:00:00Z",
    "gracePeriodEndsAt": null,
    "daysUntilExpiry": 14,
    "graceDaysRemaining": 0,
    "createdAt": "...",
    "updatedAt": "..."
  },
  "payments": [
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
      "createdAt": "...",
      "paidAt": "...",
      "failedAt": null,
      "failureReason": null
    }
  ]
}
```

Status enum: `ACTIVE` / `GRACE` / `EXPIRED`. Source: `TRIAL` / `PAID`.  
`status` her okuma anında recompute edilir (cron + on-read).

**GRACE örneği:** `status: "GRACE"`, `gracePeriodEndsAt: "2026-05-04T..."`, `daysUntilExpiry: 0`, `graceDaysRemaining: 4`.

---

## 6.2 GET `/api/v1/subscription/pricing`

**Response 200:**

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

`tableCount = 0` → checkout açılmamalı, "Add a table to subscribe" empty state.

---

## 6.3 POST `/api/v1/subscription/checkout`

**Request:**

```json
{ "months": 3 }
```

`months` 1-12.

**Response 201:**

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
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "...",
  "paidAt": null,
  "failedAt": null,
  "failureReason": null
}
```

MOCK mode'da `paymentUrl: null` ve `providerPaymentId: null`. Mobile UI mock confirm ekranını gösterir.

**Errors:**
| HTTP | code |
|------|------|
| 422 | `INVALID_DURATION` |
| 422 | `NO_TABLES` |
| 502 | `PAYMENT_PROVIDER_ERROR` (FINIK çağrılırsa — anlaşma sonrası) |
| 403 | `FORBIDDEN` (MANAGER) |

---

## 6.4 GET `/api/v1/subscription/payment/{id}`

Polling endpoint. Mobile 3rd-party redirect'ten dönünce 5sn aralıkla pollar.

**Response 200:** Yukarıdaki `PaymentResponse` ile aynı yapı. `status` = `PENDING` / `PAID` / `FAILED`.

**Errors:** `404 PAYMENT_NOT_FOUND` (başka owner'ın payment'ı da 404).

---

## 6.5 POST `/api/v1/subscription/payment/{id}/confirm` — MOCK only

**Request:**

```json
{ "outcome": "PAID" }
```

veya

```json
{ "outcome": "FAILED" }
```

**Response 200:** Güncellenmiş `PaymentResponse`.

`PAID` → subscription `endDate` uzatılır, `status: ACTIVE`, `source: PAID`.  
`FAILED` → payment status `FAILED`, subscription değişmez.

**Errors:**
| HTTP | code |
|------|------|
| 404 | `PAYMENT_NOT_FOUND` veya endpoint disabled (PAYMENT_PROVIDER != MOCK) |
| 409 | `PAYMENT_ALREADY_PROCESSED` |
| 422 | `VALIDATION_ERROR` |
| 403 | `FORBIDDEN` |

> Production'da `PAYMENT_PROVIDER=FINIK` set edildiğinde bu endpoint 404 döner — Finik kendi webhook'ı ile çalışır.

---

# 7. Ops — `/actuator/**`

## 7.1 GET `/actuator/health` — Public

**Response 200:**

```json
{
  "status": "UP"
}
```

K8s/Docker liveness/readiness probe için kullanılır.

---

# 8. Test Senaryoları (Önerilen Akış)

## 8.1 Owner Happy Path

1. `POST /api/v1/auth/register` → OWNER, 200 + auto-TRIAL
2. `GET /api/v1/subscription` → TRIAL, `daysUntilExpiry: 14`
3. `POST /api/v1/venue/create` → 201 (selected=true)
4. `POST /api/v1/table/create` → 201 (venueId=above)
5. `GET /api/v1/venue/selected` → venue + tables (session=null)
6. `POST /api/v1/session/start` → SessionLite ACTIVE
7. `POST /api/v1/session/{id}/pause` → SessionLite PAUSED
8. `POST /api/v1/session/{id}/resume` → SessionLite ACTIVE
9. `POST /api/v1/session/{id}/finish` → SessionResult COMPLETED
10. `GET /api/v1/reports/overview?period=TODAY&from=...&to=...&venueId=...` → totalRevenue updated

## 8.2 Manager Onboarding

1. Owner: `POST /api/v1/auth/invite-code` → `{code: "INVITE-..."}`
2. Manager: `POST /api/v1/auth/register` with `inviteCode, role:MANAGER`
3. Manager: `GET /api/v1/venue/list` → owner'ın venue'lerini görür (boş değil!)
4. Manager: `POST /api/v1/session/start` (kendi owner'ının masası) → 201
5. Manager: `POST /api/v1/session/start` (başka owner'ın masası) → 403 FORBIDDEN

## 8.3 Subscription Gate

1. Owner: TRIAL'ın endDate'ini manuel olarak geçmişe çek (test için)
2. Daily cron olmadan da next GET `/api/v1/subscription` → status `GRACE` (anlık recompute)
3. `POST /api/v1/session/start` → `403 SUBSCRIPTION_REQUIRED`
4. `POST /api/v1/subscription/checkout {months:1}` → 201 PENDING
5. `POST /api/v1/subscription/payment/{id}/confirm {outcome:"PAID"}` → 200 PAID + subscription ACTIVE
6. `POST /api/v1/session/start` → 201 OK

## 8.4 Auth Refresh Flow

1. `POST /api/v1/auth/login` → `{user, accessToken, refreshToken}`
2. Wait 15 dakika (access token expire) — opsiyonel test
3. Authenticated endpoint → 401 SESSION_EXPIRED
4. `POST /api/v1/auth/refresh {refreshToken}` → `{accessToken, refreshToken}` (user YOK)
5. Original request retry → 200 OK
6. **Aynı eski refresh tekrar kullan** → 401 (rotation: token tek seferlik)

## 8.5 Validation Errors

1. `POST /api/v1/auth/register` boş email → 422 details: `[{field:"email", rule:"NotBlank", ...}]`
2. `POST /api/v1/venue/create` name="" → 422 details: `[{field:"name", rule:"NotBlank"}]`
3. `POST /api/v1/session/{id}/finish` `discountPercent: 150` → 422 details: `[{field:"discountPercent", rule:"Max"}]`

---

# 9. Tam Hata Kodu Referansı

40+ hata kodu, 3 dilde (`en`, `ru`, `ky`) `messages_*.properties`'te tanımlı.

| Kategori         | Kodlar                                                                                                                                                                                                              |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Auth**         | `INVALID_CREDENTIALS` (401), `INVALID_INVITE_CODE` (400), `EMAIL_ALREADY_USED` (409), `PHONE_ALREADY_USED` (409), `ACCOUNT_LOCKED` (423), `SESSION_EXPIRED` (401), `UNAUTHORIZED` (401), `INVALID_TOKEN_TYPE` (401) |
| **Generic**      | `FORBIDDEN` (403), `VALIDATION_ERROR` (422), `BAD_REQUEST` (400), `INTERNAL_SERVER_ERROR` (500), `SERVICE_UNAVAILABLE` (503)                                                                                        |
| **Venue/Table**  | `VENUE_NOT_FOUND` (404), `TABLE_NOT_FOUND` (404), `VENUE_NUMBER_TAKEN` (409), `TABLE_NUMBER_TAKEN` (409), `VENUE_HAS_TABLES` (409), `TABLE_HAS_ACTIVE_SESSION` (409)                                                |
| **Session**      | `SESSION_NOT_FOUND` (404), `SESSION_NOT_ACTIVE` (409), `SESSION_NOT_PAUSED` (409), `SESSION_ALREADY_COMPLETED` (409), `SESSION_ALREADY_CANCELLED` (409), `CANCEL_WINDOW_EXPIRED` (422), `INVALID_DISCOUNT` (422)    |
| **Reports**      | `REPORT_NOT_FOUND` (404), `MANAGER_NOT_FOUND` (404), `NOT_ENOUGH_DATA` (422)                                                                                                                                        |
| **Managers**     | `HAS_ACTIVE_SESSION` (409)                                                                                                                                                                                          |
| **Subscription** | `SUBSCRIPTION_REQUIRED` (403), `NO_TABLES` (422), `INVALID_DURATION` (422), `PAYMENT_NOT_FOUND` (404), `PAYMENT_ALREADY_PROCESSED` (409), `PAYMENT_PROVIDER_ERROR` (502), `PRICING_MISMATCH` (409)                  |

---

# 10. Bilinen Sapmalar / Sınırlamalar (MVP)

- **forgot-password** → `503` (mail flow Phase 5'te eklenecek)
- **Finik provider** → mock-only. `PAYMENT_PROVIDER=FINIK` set edilirse checkout `502 PAYMENT_PROVIDER_ERROR`. Anlaşma sonrası açılacak.
- **Heatmap timezone** → UTC sabit. Venue-local timezone v2'de.
- **Currency** → her masa kendi currency'sini taşır, reports tek currency varsayar (multi-currency mix v2'de).
- **Rate limiting** → şu anlık yok.
- **Test suite** → Phase 5'e ertelendi.

---

## Hata Bildirme

Bug bulursan:

1. Endpoint + request + response (header'lar dahil)
2. Beklenen vs aktüel
3. `Accept-Language` ne gönderildi
4. Owner mı Manager mı (role)
5. Subscription status (gate ile alakalı olabilir)

Backend ekibine direkt veya GitHub issue açabilirsin.
