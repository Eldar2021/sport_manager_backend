# Home Page API

Kullanıcı login ya da register olduğunda direkt olarak Home Page'e yönlendirilir. Home Page; seçili mekanı ve o mekana ait masaları gösterir.

**Base URL:** `<BASE_URL>` (mobil tarafta `--dart-define=BASE_URL=...` ile geliyor)
**Content-Type:** `application/json; charset=utf-8`

---

## Global Headers (her istekte)

| Header            | Source                                                       | Example             | Required    |
| ----------------- | ------------------------------------------------------------ | ------------------- | ----------- |
| `Accept-Language` | Cihaz dili (`en` / `ru` / `ky`)                              | `ru`                | Optional    |
| `versionBuild`    | App build number                                             | `42`                | Optional    |
| `os`              | Platform                                                     | `ios` / `android`   | Optional    |
| `Authorization`   | `Bearer <accessToken>` — sadece authenticated endpoint'lerde | `Bearer eyJhbGc...` | Conditional |

> **Not:** `Accept-Language` set edilmişse error mesajları sadece o dilde dönebilir (response boyutunu küçültmek için). Client iletmediyse default olarak `en`+`ru`+`ky` üçü birden döner.

---

## Authorization Roller

| Endpoint                       | OWNER | MANAGER |
| ------------------------------ | :---: | :-----: |
| GET `/api/v1/venue/list`       |  ✅   |   ✅    |
| GET `/api/v1/venue/selected`   |  ✅   |   ✅    |
| PATCH `/api/v1/venue/selected` |  ✅   |   ✅    |
| POST `/api/v1/venue/create`    |  ✅   |   ❌    |
| PUT `/api/v1/venue/{id}`       |  ✅   |   ❌    |
| DELETE `/api/v1/venue/{id}`    |  ✅   |   ❌    |
| POST `/api/v1/table/create`    |  ✅   |   ❌    |
| PUT `/api/v1/table/{id}`       |  ✅   |   ❌    |
| DELETE `/api/v1/table/{id}`    |  ✅   |   ❌    |

Yetkisiz erişim → **403 FORBIDDEN**.

---

## Standart Error Yapısı

Tüm hata response'ları aşağıdaki formatta gelir:

```json
{
  "code": "VENUE_NOT_FOUND",
  "message": {
    "en": "Selected venue not found",
    "ru": "Выбранное место не найдено",
    "ky": "Тандалган мекен табылган жок"
  },
  "details": null
}
```

Validation hatası ise `details` field'ı doldurulur:

```json
{
  "code": "VALIDATION_ERROR",
  "message": {
    "en": "Validation failed",
    "ru": "Ошибка валидации",
    "ky": "Валидация катасы"
  },
  "details": [
    {
      "field": "name",
      "rule": "max_length",
      "message": "Name must be at most 100 characters"
    },
    {
      "field": "number",
      "rule": "min_value",
      "message": "Number must be at least 1"
    }
  ]
}
```

### Error Code Listesi

| Code                       | HTTP | Anlamı                                                                                                                                              |
| -------------------------- | :--: | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VALIDATION_ERROR`         | 422  | Body validation başarısız                                                                                                                           |
| `BAD_REQUEST`              | 400  | Geçersiz istek                                                                                                                                      |
| `UNAUTHORIZED`             | 401  | Token yok / geçersiz / süresi dolmuş                                                                                                                |
| `FORBIDDEN`                | 403  | Bu işlem için yetkin yok                                                                                                                            |
| `VENUE_NOT_FOUND`          | 404  | Mekan bulunamadı                                                                                                                                    |
| `TABLE_NOT_FOUND`          | 404  | Masa bulunamadı                                                                                                                                     |
| `VENUE_NUMBER_TAKEN`       | 409  | Bu mekan numarası zaten kullanılıyor                                                                                                                |
| `TABLE_NUMBER_TAKEN`       | 409  | Bu masa numarası mekanda zaten var                                                                                                                  |
| `VENUE_HAS_TABLES`         | 409  | Mekanın içinde masalar var, önce onları sil                                                                                                         |
| `TABLE_HAS_ACTIVE_SESSION` | 409  | Masada aktif session var, silinemez                                                                                                                 |
| `SUBSCRIPTION_REQUIRED`    | 403  | Owner aboneliği `EXPIRED` veya `GRACE@0` (yazma gate; bkz. [subscription-api.md](subscription-api.md#subscription-gate--diğer-endpointlere-etkisi)) |
| `INTERNAL_SERVER_ERROR`    | 500  | Beklenmeyen sunucu hatası                                                                                                                           |
| `SERVICE_UNAVAILABLE`      | 503  | Servis geçici olarak kullanılamıyor                                                                                                                 |

---

## Domain Modelleri

### Venue

```ts
{
  id: string (uuid),
  name: string (1-100 chars),
  number: integer (>= 1),     // owner içinde unique
  address: string | null (0-255 chars),
  selected: boolean,
  tableCount: integer,
  createdAt: string (ISO 8601),
  updatedAt: string (ISO 8601)
}
```

### Table

```ts
{
  id: string (uuid),
  venueId: string (uuid),
  name: string | null (0-100 chars),
  number: integer (>= 1),     // venue içinde unique
  description: string | null (0-500 chars),
  tarifAmount: integer (1-1000000), // tam sayı, kuruş yok
  currency: "KGS" | "USD" | "RUB" | "KZT" | "TRY",
  tarifType: "MINUTE" | "HOUR" | "DAY",
  session: Session | null,
  createdAt: string (ISO 8601),
  updatedAt: string (ISO 8601)
}
```

### Session

```ts
{
  id: string (uuid),
  tableId: string (uuid),
  managerId: string (uuid),       // session'ı başlatan kullanıcı (owner veya manager)
  isActive: boolean,
  isPaused: boolean,
  startedAt: string (ISO 8601),
  pausedAt: string (ISO 8601) | null,
  resumedAt: string (ISO 8601) | null,
  totalPausedSeconds: integer,    // bu session'da toplam ne kadar duraklatıldı
  tarifAmountSnapshot: integer,   // session başlarken masa fiyatı
  tarifTypeSnapshot: "MINUTE" | "HOUR" | "DAY"
}
```

> **Snapshot kuralı:** Session başladığında masa fiyatı kopyalanır. Owner session ortasında fiyatı değiştirirse mevcut session etkilenmez.
>
> **`managerId`:** Session'ı başlatan kullanıcının ID'si. Owner kendisi başlattıysa owner'ın ID'si, manager başlattıysa manager'ın ID'si. Reports tarafı (manager performans/fraud sinyali) bu alanı kullanır. Bkz. [reports-api.md](reports-api.md).

### SelectedVenueResponse

`GET /venue/selected` ve `PATCH /venue/selected` endpoint'lerinin döndürdüğü yapı:

```ts
{
  venue: Venue,
  tables: Table[]
}
```

---

## Endpoint'ler

### 1. Get Venue List

Mekan seçici (bottom sheet) açıldığında çağrılır. Kullanıcının erişebildiği tüm mekanları döner. Hafif response — masalar ve session bilgisi içermez (performans için).

**Endpoint:**

```
GET /api/v1/venue/list
```

**Headers:** `Authorization: Bearer <accessToken>`

**Response — Başarılı (200):**

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Merkez Şube",
    "number": 1,
    "address": "Chui Avenue 132, Bishkek",
    "selected": true,
    "tableCount": 3,
    "createdAt": "2026-04-15T10:30:00.000Z",
    "updatedAt": "2026-04-20T14:22:00.000Z"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "name": "Botanika Şubesi",
    "number": 2,
    "address": "Tynystanov 89, Bishkek",
    "selected": false,
    "tableCount": 5,
    "createdAt": "2026-04-18T11:00:00.000Z",
    "updatedAt": "2026-04-18T11:00:00.000Z"
  }
]
```

**Response — Hiç mekan yok (200):**

```json
[]
```

> Mobile boş array gelirse "Create Venue" CTA'sı gösterir.

**Notlar:**

- Owner için: kendisinin sahip olduğu tüm mekanlar.
- Manager için: bağlı olduğu owner'ın tüm mekanları.
- Listede her zaman bir tane `selected: true` olur (eğer en az bir mekan varsa).
- Sıralama: `number` artan, tie-break için `createdAt` artan.

---

### 2. Get Selected Venue

Home ekran açıldığında çağrılır. Kullanıcının seçili mekanını + masalarını döner.

**Endpoint:**

```
GET /api/v1/venue/selected
```

**Headers:** `Authorization: Bearer <accessToken>`

**Response — Hiç mekan yok (404):**

```json
{
  "code": "VENUE_NOT_FOUND",
  "message": {
    "en": "No venues found. Please create a venue first.",
    "ru": "Места не найдены. Пожалуйста, создайте место.",
    "ky": "Мекендер табылган жок. Алгач мекен түзүңүз."
  },
  "details": null
}
```

→ Mobile bu hatayı alınca "Create Venue" ekranına yönlendirir.

**Response — Başarılı (200):**

```json
{
  "venue": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Merkez Şube",
    "number": 1,
    "address": "Chui Avenue 132, Bishkek",
    "selected": true,
    "tableCount": 3,
    "createdAt": "2026-04-15T10:30:00.000Z",
    "updatedAt": "2026-04-20T14:22:00.000Z"
  },
  "tables": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "venueId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "VIP Salon",
      "number": 1,
      "description": "8-ball pool table",
      "tarifAmount": 250,
      "currency": "KGS",
      "tarifType": "HOUR",
      "session": {
        "id": "770e8400-e29b-41d4-a716-446655440002",
        "tableId": "660e8400-e29b-41d4-a716-446655440001",
        "managerId": "user-101",
        "isActive": true,
        "isPaused": false,
        "startedAt": "2026-04-27T18:42:00.000Z",
        "pausedAt": null,
        "resumedAt": null,
        "totalPausedSeconds": 0,
        "tarifAmountSnapshot": 250,
        "tarifTypeSnapshot": "HOUR"
      },
      "createdAt": "2026-04-15T10:35:00.000Z",
      "updatedAt": "2026-04-27T18:42:00.000Z"
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440003",
      "venueId": "550e8400-e29b-41d4-a716-446655440000",
      "name": null,
      "number": 2,
      "description": null,
      "tarifAmount": 200,
      "currency": "KGS",
      "tarifType": "HOUR",
      "session": null,
      "createdAt": "2026-04-15T10:36:00.000Z",
      "updatedAt": "2026-04-15T10:36:00.000Z"
    }
  ]
}
```

**Notlar:**

- `tables` boş array olarak da gelebilir → mobile "create table" ekranına yönlendirir.
- Eğer kullanıcının manuel olarak seçtiği bir mekanı yoksa, en eski (createdAt'e göre ilk) mekan otomatik selected gelir ve backend'de bu durum kaydedilir.

---

### 3. Update Selected Venue

Kullanıcı mekan seçici (bottom sheet) üzerinden başka bir mekana geçmek istediğinde. Yeni seçili mekanın tam datası (masalar dahil) döner — mobile böylece ek bir istek atmadan home'u günceller.

**Endpoint:**

```
PATCH /api/v1/venue/selected
```

**Headers:** `Authorization: Bearer <accessToken>`

**Body:**

```json
{
  "venueId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response (200):**

`GET /venue/selected` ile aynı yapıda — `venue` ve `tables` alanları döner:

```json
{
  "venue": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Merkez Şube",
    "number": 1,
    "address": "Chui Avenue 132, Bishkek",
    "selected": true,
    "tableCount": 3,
    "createdAt": "2026-04-15T10:30:00.000Z",
    "updatedAt": "2026-04-27T19:00:00.000Z"
  },
  "tables": [
    // ... bu mekanın masaları
  ]
}
```

**Errors:**

- `404 VENUE_NOT_FOUND` — mekan bulunamadı veya kullanıcıya ait değil
- `403 FORBIDDEN` — başka bir owner'ın mekanı

---

### 4. Create Venue

**Endpoint:**

```
POST /api/v1/venue/create
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Body:**

```json
{
  "name": "Merkez Şube",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek"
}
```

**Validation:**
| Field | Type | Required | Rules |
| ------- | ------- | :------: | ------------------------------ |
| name | string | ✅ | 1-100 karakter |
| number | integer | ✅ | >= 1, owner içinde unique |
| address | string | ❌ | 0-255 karakter (null olabilir) |

**Response (201):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Merkez Şube",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek",
  "selected": false,
  "tableCount": 0,
  "createdAt": "2026-04-27T18:42:00.000Z",
  "updatedAt": "2026-04-27T18:42:00.000Z"
}
```

**Notlar:**

- İlk mekan oluşturulursa otomatik `selected: true` olur.
- Sonraki mekanlar `selected: false` olarak başlar.

**Errors:**

- `422 VALIDATION_ERROR`
- `409 VENUE_NUMBER_TAKEN` — bu numara zaten kullanılıyor
- `403 FORBIDDEN` — manager bu endpoint'i çağıramaz
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 5. Update Venue

**Endpoint:**

```
PUT /api/v1/venue/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Path Params:**

- `id` (uuid) — mekan ID'si

**Body:**

```json
{
  "name": "Merkez Şube (Güncellenmiş)",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek"
}
```

> **Not:** `id` body'de gönderilmez, URL'den alınır.

**Response (200):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Merkez Şube (Güncellenmiş)",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek",
  "selected": true,
  "tableCount": 3,
  "createdAt": "2026-04-15T10:30:00.000Z",
  "updatedAt": "2026-04-27T18:45:00.000Z"
}
```

**Errors:**

- `404 VENUE_NOT_FOUND`
- `409 VENUE_NUMBER_TAKEN` — yeni number başka bir mekanda kullanılıyor
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 6. Delete Venue

**Endpoint:**

```
DELETE /api/v1/venue/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Response (200):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleted": true
}
```

**Notlar:**

- **Soft delete** uygulanır (`deletedAt` set edilir, kayıt fiziksel silinmez). Geçmiş raporlar için kritik.
- Silinen mekanın masaları da soft delete edilir (cascade).
- Eğer silinen mekan `selected: true` ise, kullanıcının başka mekanı varsa en eski olan otomatik seçili olur.
- Aktif session'ı olan masa varsa **silinemez** → `409 TABLE_HAS_ACTIVE_SESSION`.

**Errors:**

- `404 VENUE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION` — içerideki bir masada aktif session var
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 7. Create Table

**Endpoint:**

```
POST /api/v1/table/create
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Body:**

```json
{
  "venueId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "VIP Salon",
  "number": 1,
  "description": "8-ball pool table",
  "tarifAmount": 250,
  "currency": "KGS",
  "tarifType": "HOUR"
}
```

**Validation:**
| Field | Type | Required | Rules |
| ----------- | ------- | :------: | ------------------------------------------- |
| venueId | uuid | ✅ | Owner'ın sahip olduğu venue |
| name | string | ❌ | 0-100 karakter |
| number | integer | ✅ | >= 1, venue içinde unique |
| description | string | ❌ | 0-500 karakter |
| tarifAmount | integer | ✅ | 1-1.000.000 (tam sayı, ondalık yok) |
| currency | enum | ✅ | `KGS`, `USD`, `RUB`, `KZT`, `TRY` |
| tarifType | enum | ✅ | `MINUTE`, `HOUR`, `DAY` |

**Response (201):**

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "venueId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "VIP Salon",
  "number": 1,
  "description": "8-ball pool table",
  "tarifAmount": 250,
  "currency": "KGS",
  "tarifType": "HOUR",
  "session": null,
  "createdAt": "2026-04-27T18:42:00.000Z",
  "updatedAt": "2026-04-27T18:42:00.000Z"
}
```

**Errors:**

- `404 VENUE_NOT_FOUND`
- `409 TABLE_NUMBER_TAKEN` — bu venue içinde aynı numarada masa var
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 8. Update Table

**Endpoint:**

```
PUT /api/v1/table/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Path Params:**

- `id` (uuid) — masa ID'si

**Body:**

```json
{
  "name": "VIP Salon",
  "number": 1,
  "description": "8-ball pool table",
  "tarifAmount": 300,
  "currency": "KGS",
  "tarifType": "HOUR"
}
```

> **Not:** `id` ve `venueId` URL'den alınır, body'de yok. Masa başka bir mekana taşınamaz (MVP scope dışı).

**Response (200):**

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "venueId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "VIP Salon",
  "number": 1,
  "description": "8-ball pool table",
  "tarifAmount": 300,
  "currency": "KGS",
  "tarifType": "HOUR",
  "session": null,
  "createdAt": "2026-04-15T10:35:00.000Z",
  "updatedAt": "2026-04-27T18:50:00.000Z"
}
```

**Önemli:** Aktif session varken `tarifAmount` veya `tarifType` değişirse, **mevcut session etkilenmez** (snapshot kullanılır). Yeni başlayan session'lar yeni fiyattan hesaplanır.

**Errors:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_NUMBER_TAKEN`
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 9. Delete Table

**Endpoint:**

```
DELETE /api/v1/table/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (Owner only)

**Response (200):**

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "deleted": true
}
```

**Notlar:**

- Soft delete.
- Aktif session varsa silinemez.

**Errors:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

## Endpoint Özeti

| Method | Path                     | Auth     | Rate Limit |
| ------ | ------------------------ | -------- | ---------- |
| GET    | `/api/v1/venue/list`     | Required | 60/dakika  |
| GET    | `/api/v1/venue/selected` | Required | 60/dakika  |
| PATCH  | `/api/v1/venue/selected` | Required | 30/dakika  |
| POST   | `/api/v1/venue/create`   | Owner    | 10/dakika  |
| PUT    | `/api/v1/venue/{id}`     | Owner    | 30/dakika  |
| DELETE | `/api/v1/venue/{id}`     | Owner    | 10/dakika  |
| POST   | `/api/v1/table/create`   | Owner    | 30/dakika  |
| PUT    | `/api/v1/table/{id}`     | Owner    | 60/dakika  |
| DELETE | `/api/v1/table/{id}`     | Owner    | 30/dakika  |

---

## Açık Sorular / Karar Verilecekler

- [ ] `Accept-Language` ile single-language error response mu yoksa her zaman multi-language mı? (Tavsiyem: multi-language, basit ve client esnek kullanır)
- [ ] Currency listesi sabit mi, yoksa backend'den config endpoint'i ile mi gelecek? (Tavsiyem: MVP'de hardcode 5 para birimi, v2'de config endpoint)
- [ ] Manager'a venue/table listesi salt-okunur olarak görünsün mü? (Tavsiyem: evet, manager Home'da masaları görüyor zaten)
- [ ] `tarifType: DAY` MVP'de gerekli mi? Çoğu salon saatlik çalışıyor. (Tavsiyem: HOUR ve MINUTE yeterli, DAY v2'ye)
- [ ] Mekanı silerken aktif session varsa "force delete" parametresi olsun mu? (Tavsiyem: hayır, owner önce session'ları bitirmek zorunda)
