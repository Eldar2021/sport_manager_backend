# Session Management API

Masa kullanım sürelerini yöneten endpoint'ler. Bir masada müşteri oturduğunda session başlar, müşteri kalktığında session biter ve ödeme alınır. Arada müşteri ara verirse session duraklatılabilir.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Authorization:** Tüm endpoint'ler `Bearer <accessToken>` gerektirir.

---

## Genel Mantık ve Kurallar

### Session Yaşam Döngüsü

```
                     ┌──────────────┐
                     │ Masa boş     │
                     │ session=null │
                     └──────┬───────┘
                            │ start
                            ▼
                     ┌──────────────┐
                     │ ACTIVE       │◄──────────┐
                     └──┬────┬──────┘           │
                        │    │                  │ resume
                  pause │    │ finish           │
                        ▼    │                  │
                 ┌──────────┐│            ┌─────┴──────┐
                 │ PAUSED   │└─────────►  │ ACTIVE     │
                 │          │             │ (devam)    │
                 └────┬─────┘             └────────────┘
                      │ finish
                      ▼
               ┌──────────────┐
               │ COMPLETED    │ ← session DB'ye yazıldı
               │ ödeme alındı │   (raporlara dahil)
               └──────────────┘

  start/active iken            finish iken
  ───────────────              ─────────────
  cancel (60sn)
  ───────────────
   CANCELLED
   (raporlara dahil değil)
```

### Kritik Kurallar

1. **Tüm timestamp'leri backend yazar.** Client `startedAt`, `pausedAt`, `resumedAt`, `endedAt` göndermez. Bu, manager'ın telefon saatini değiştirerek sahte hesaplama yapmasını engeller — **owner'ı manager'dan koruyan en kritik kural**.

2. **Snapshot kuralı.** Session başladığında masanın `tarifAmount` ve `tarifType` değerleri kopyalanır. Owner session ortasında fiyat değiştirse mevcut session etkilenmez.

3. **Session ID kullan, tableId değil.** `pause`, `resume`, `finish`, `cancel` endpoint'lerinde her zaman `sessionId` ile çalışılır. Bir masada zamanla birden çok session olabilir.

4. **`tableId` sadece `start` için.** Yeni session başlatırken tableId veriyoruz çünkü henüz session yok.

5. **Bir masada aynı anda sadece 1 aktif session olur.** Aktif session = `status = ACTIVE` veya `status = PAUSED`. Aynı masaya ikinci start denemesi → `409 TABLE_HAS_ACTIVE_SESSION`.

6. **Tamamlanmış session yeniden açılamaz.** Müşteri çıktıktan sonra masa yeniden boştur, yeni müşteri için yeni bir session başlatılabilir. **Aynı masa günde 20 kez kullanılabilir.**

7. **Pause history backend'de tutulur, mobile'a dönmez.** Mobile sadece `totalPausedSeconds` ile sayacı doğru çalıştırır. Detaylı pause kayıtları DB'de kalır (audit/rapor için).

---

## Authorization Rolleri

| Endpoint                           | OWNER | MANAGER |
| ---------------------------------- | :---: | :-----: |
| POST `/api/v1/session/start`       |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/pause`  |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/resume` |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/finish` |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/cancel` |  ✅   |  ✅\*   |

> \*Manager `cancel` çağırabilir ama sadece **session başladıktan sonraki ilk 60 saniye içinde**. Bu süre dolduktan sonra sadece owner cancel edebilir.

---

## Response Modelleri

İki ayrı response tipi var. Endpoint'e göre hangisinin döneceği belli.

### SessionLite

Aktif (ACTIVE veya PAUSED) session için. `start`, `pause`, `resume` response'larında ve Home ekranında masa kartı içinde dönen yapı.

```ts
{
  id: string (uuid),
  tableId: string (uuid),
  managerId: string (uuid),            // session'ı başlatan kullanıcı (owner veya manager)
  status: "ACTIVE" | "PAUSED",
  startedAt: string (ISO 8601),
  totalPausedSeconds: integer,         // mobile sayaç hesabı için
  pausedAt: string (ISO 8601) | null,  // PAUSED ise dolu, ACTIVE ise null
  tarifAmountSnapshot: integer,
  tarifTypeSnapshot: "MINUTE" | "HOUR" | "DAY"
}
```

### SessionResult

Bitmiş session için. `finish` ve `cancel` response'larında dönen yapı.

```ts
{
  id: string (uuid),
  tableId: string (uuid),
  managerId: string (uuid),            // session'ı başlatan kullanıcı (kimlik audit)
  status: "COMPLETED" | "CANCELLED",
  startedAt: string (ISO 8601),
  endedAt: string (ISO 8601),

  // COMPLETED ise dolu, CANCELLED ise null:
  durationSeconds: integer | null,
  subtotal: integer | null,
  discountPercent: integer | null,
  totalAmount: integer | null,

  // CANCELLED ise dolu, COMPLETED ise null:
  cancelReason: string | null
}
```

> **`managerId`:** Session'ı başlatan kullanıcının ID'si (owner kendisi başlattıysa owner'ın ID'si, manager başlattıysa manager'ın ID'si). Reports tarafı (manager performans / fraud sinyalleri) bu alanı kullanır. Bkz. [reports-api.md](reports-api.md).

### Hesaplama Formülleri

```
billableSeconds = (endedAt - startedAt) - totalPausedSeconds

# tarifType'a göre:
if HOUR:    billableUnits = billableSeconds / 3600
if MINUTE:  billableUnits = billableSeconds / 60
if DAY:     billableUnits = billableSeconds / 86400

subtotal       = round(billableUnits * tarifAmountSnapshot)
discountAmount = round(subtotal * discountPercent / 100)
totalAmount    = subtotal - discountAmount
```

> **Yuvarlama:** Standart matematiksel yuvarlama (0.5 → yukarı). Kuruş yok, tüm tutarlar tam sayı.

---

## Standart Error Yapısı

```json
{
  "code": "TABLE_HAS_ACTIVE_SESSION",
  "message": {
    "en": "Table already has an active session",
    "ru": "Стол уже имеет активную сессию",
    "ky": "Стол активдүү сессияга ээ"
  },
  "details": null
}
```

### Session-spesifik Error Code'ları

| Code                        | HTTP | Anlamı                                                  |
| --------------------------- | :--: | ------------------------------------------------------- |
| `SESSION_NOT_FOUND`         | 404  | Session bulunamadı                                      |
| `TABLE_NOT_FOUND`           | 404  | Masa bulunamadı                                         |
| `TABLE_HAS_ACTIVE_SESSION`  | 409  | Masada zaten aktif/paused session var                   |
| `SESSION_NOT_ACTIVE`        | 409  | Session ACTIVE değil (örn: zaten paused veya completed) |
| `SESSION_NOT_PAUSED`        | 409  | Session PAUSED değil (resume edilemez)                  |
| `SESSION_ALREADY_COMPLETED` | 409  | Session zaten tamamlanmış, işlem yapılamaz              |
| `CANCEL_WINDOW_EXPIRED`     | 422  | 60 saniyelik iptal süresi doldu                         |
| `INVALID_DISCOUNT`          | 422  | İndirim yüzdesi 0-100 aralığında değil                  |
| `SUBSCRIPTION_REQUIRED`     | 403  | Owner aboneliği `EXPIRED` veya `GRACE@0` (yazma gate; bkz. [subscription-api.md](subscription-api.md#subscription-gate--diğer-endpointlere-etkisi)) |

---

## Endpoints

### 1. Start Session

Boş bir masaya tıklandığında manager start butonuna basar. Yeni bir session başlar.

**Endpoint:**

```
POST /api/v1/session/start
```

**Body:**

```json
{
  "tableId": "660e8400-e29b-41d4-a716-446655440001"
}
```

> `startedAt` body'de gönderilmez. Backend kendi server saatini kullanır.

**Validation:**
| Field | Type | Required | Rules |
| -------- | ---- | :------: | ------------------------------ |
| tableId | uuid | ✅ | Kullanıcının erişebildiği masa |

**Response (201) — SessionLite:**

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tableId": "660e8400-e29b-41d4-a716-446655440001",
  "managerId": "user-101",
  "status": "ACTIVE",
  "startedAt": "2026-04-27T18:42:00.000Z",
  "totalPausedSeconds": 0,
  "pausedAt": null,
  "tarifAmountSnapshot": 250,
  "tarifTypeSnapshot": "HOUR"
}
```

**Errors:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

**Race condition:** Backend transaction içinde masayı lock'lar. İki paralel start denemesi gelirse biri başarılı olur, diğeri 409 alır.

---

### 2. Pause Session

Müşteri sigaraya/tuvalete çıktığında session duraklatılır. Sayaç durur.

**Endpoint:**

```
POST /api/v1/session/{id}/pause
```

**Path Params:**

- `id` (uuid) — session ID

**Body:** Boş — `{}`

> `pausedAt` body'de gönderilmez. Backend server saatini yazar.

**Response (200) — SessionLite:**

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tableId": "660e8400-e29b-41d4-a716-446655440001",
  "managerId": "user-101",
  "status": "PAUSED",
  "startedAt": "2026-04-27T18:42:00.000Z",
  "totalPausedSeconds": 0,
  "pausedAt": "2026-04-27T19:02:00.000Z",
  "tarifAmountSnapshot": 250,
  "tarifTypeSnapshot": "HOUR"
}
```

**Notlar:**

- Bir session **birden fazla kez** pause/resume edilebilir.
- Pause açıkken `totalPausedSeconds` henüz güncellenmez — resume olunca güncellenir.

**Errors:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_NOT_ACTIVE`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 3. Resume Session

Pause'dan dönüş.

**Endpoint:**

```
POST /api/v1/session/{id}/resume
```

**Path Params:**

- `id` (uuid) — session ID

**Body:** Boş — `{}`

**Response (200) — SessionLite:**

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tableId": "660e8400-e29b-41d4-a716-446655440001",
  "managerId": "user-101",
  "status": "ACTIVE",
  "startedAt": "2026-04-27T18:42:00.000Z",
  "totalPausedSeconds": 600,
  "pausedAt": null,
  "tarifAmountSnapshot": 250,
  "tarifTypeSnapshot": "HOUR"
}
```

**Notlar:**

- `totalPausedSeconds` artık güncellenmiş halde (10 dakika = 600 saniye).
- Yeni bir pause olursa `totalPausedSeconds` toplanarak büyür.
- `pausedAt` artık null (PAUSED durumdan ACTIVE'e geçti).

**Errors:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_NOT_PAUSED`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 4. Finish Session

Müşteri çıkıyor, ödeme alınıyor, session bitiriliyor. **Tek adımda hem hesaplama hem kayıt yapılır.**

**Endpoint:**

```
POST /api/v1/session/{id}/finish
```

**Path Params:**

- `id` (uuid) — session ID

**Body:**

```json
{
  "discountPercent": 10
}
```

**Validation:**
| Field | Type | Required | Rules |
| --------------- | ------- | :------: | ---------------- |
| discountPercent | integer | ❌ | 0-100, default 0 |

> `endedAt` body'de gönderilmez. Backend server saatini yazar.

**Backend mantığı:**

1. Session PAUSED durumdaysa otomatik resume edilir, sonra finish.
2. `endedAt` = now (server time)
3. `durationSeconds` = (endedAt - startedAt) - totalPausedSeconds
4. `subtotal` = round(durationSeconds / unitDivisor × tarifAmountSnapshot)
5. `totalAmount` = subtotal - round(subtotal × discountPercent / 100)
6. Status = COMPLETED, masa boşalır.

**Response (200) — SessionResult:**

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tableId": "660e8400-e29b-41d4-a716-446655440001",
  "managerId": "user-101",
  "status": "COMPLETED",
  "startedAt": "2026-04-27T18:42:00.000Z",
  "endedAt": "2026-04-27T20:12:00.000Z",
  "durationSeconds": 4800,
  "subtotal": 333,
  "discountPercent": 10,
  "totalAmount": 300,
  "cancelReason": null
}
```

**Hesaplama örneği:**

- Toplam süre: 18:42 → 20:12 = 90 dakika = 5400 saniye
- Pause: 600 saniye
- billableSeconds: 5400 - 600 = 4800 saniye = 80 dakika = 1.333 saat
- subtotal: round(1.333 × 250) = 333 KGS
- discount: round(333 × 10 / 100) = 33 KGS
- totalAmount: 333 - 33 = 300 KGS

**Notlar:**

- Finish edildikten sonra masa hemen yeni müşteri için kullanılabilir.

**Errors:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_ALREADY_COMPLETED`
- `422 INVALID_DISCOUNT`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

### 5. Cancel Session

"Yanlışlıkla start'a bastım" durumu. Session iptal edilir, raporlara dahil olmaz.

**Endpoint:**

```
POST /api/v1/session/{id}/cancel
```

**Path Params:**

- `id` (uuid) — session ID

**Body:**

```json
{
  "reason": "Yanlış masaya tıkladım"
}
```

**Validation:**
| Field | Type | Required | Rules |
| ------ | ------ | :------: | -------------- |
| reason | string | ✅ | 1-200 karakter |

> Manager **sadece ilk 60 saniye içinde** cancel edebilir. 60 saniye sonrası → `422 CANCEL_WINDOW_EXPIRED`. Bu kuralı geçen iptal istekleri sadece owner tarafından yapılabilir.

**Response (200) — SessionResult:**

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tableId": "660e8400-e29b-41d4-a716-446655440001",
  "managerId": "user-101",
  "status": "CANCELLED",
  "startedAt": "2026-04-27T18:42:00.000Z",
  "endedAt": "2026-04-27T18:42:30.000Z",
  "durationSeconds": null,
  "subtotal": null,
  "discountPercent": null,
  "totalAmount": null,
  "cancelReason": "Yanlış masaya tıkladım"
}
```

**Notlar:**

- Cancel edilen session masayı boşaltır.
- Cancel edilen session'lar **raporlarda gözükür** (audit için), ancak gelir hesaplamasına **dahil edilmez**.
- Owner aylık raporda "Manager X bu ay 12 session iptal etti" görebilmeli — şüpheli aktiviteyi tespit etmek için.

**Errors:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_ALREADY_COMPLETED`
- `422 CANCEL_WINDOW_EXPIRED`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — owner aboneliği `EXPIRED` / `GRACE@0`

---

## Endpoint Özeti

| Method | Path                          | Response Tipi | Auth   | Cancel Window  |
| ------ | ----------------------------- | ------------- | ------ | -------------- |
| POST   | `/api/v1/session/start`       | SessionLite   | Both   | —              |
| POST   | `/api/v1/session/{id}/pause`  | SessionLite   | Both   | —              |
| POST   | `/api/v1/session/{id}/resume` | SessionLite   | Both   | —              |
| POST   | `/api/v1/session/{id}/finish` | SessionResult | Both   | —              |
| POST   | `/api/v1/session/{id}/cancel` | SessionResult | Both\* | 60sn (manager) |

\*Manager için cancel sadece ilk 60 saniye geçerli; sonrasında sadece owner.

---

## Mobile için Notlar

### Session Sayacının Hesaplanması (Client-Side)

Manager telefonunda canlı sayaç gösterilirken:

```
# ACTIVE iken:
elapsedSeconds = (now - startedAt) - totalPausedSeconds

# PAUSED iken:
elapsedSeconds = (pausedAt - startedAt) - totalPausedSeconds
```

- Session ACTIVE iken: sayaç sürekli artar.
- Session PAUSED iken: sayaç durur, `pausedAt`'e göre sabit kalır.
- Server saati ile client saati farkı (`server_time_offset`) login sırasında hesaplanmalı, sayaçta uygulanmalı.

### Pause/Resume UI Kuralı

- Bir session ACTIVE iken: `[Duraklat]` ve `[Bitir]` butonları görünür.
- Bir session PAUSED iken: `[Devam Et]` ve `[Bitir]` butonları görünür. Sayaç gri renge döner.

### Cancel Butonunun Görünürlüğü

- İlk 60 saniye: manager için `[Yanlış başladım]` butonu görünür.
- 60 saniye sonra manager'da bu buton kaybolur.
- Owner her zaman görebilir.

---

## Açık Sorular / Karar Verilecekler

- [ ] Manager `cancel` için 60 saniye yeterli mi? Pratik test gerekli.
- [ ] Pause süresi belirsiz uzun olabilir mi? (örn: müşteri 3 saat sigaraya çıktı?) Otomatik timeout/auto-resume gerekir mi?
- [ ] `tarifType: DAY` MVP'de gerçekten gerekli mi?
- [ ] Aktif session'lar listesini almak için ayrı bir `GET /sessions/active` endpoint'i lazım mı?
