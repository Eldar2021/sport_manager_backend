# Reports REST API — Backend Contract

Owner'a gösterilen rapor / iş zekâsı ekranını besleyen uçlar. Mobil
istemci (`packages/reports`) bu uçları kullanır.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Auth:** Tüm uçlar `Authorization: Bearer <accessToken>` ister, **role = `OWNER`**.
Owner olmayan kullanıcı `403` almalı.

> Reports — Owner'ın "para nereden geliyor, masalar/manager'lar nasıl
> performans gösteriyor, bu tempoda devam edersem ay/yıl sonu ne olacak"
> sorularına net cevap veren ekran. **MVP scope** kasıtlı olarak dar:
> sadece **gelir + oturum sayısı** KPI'ları, takvim-bazlı tarih filtresi,
> tek mekan kapsamı. Fraud sinyalleri / manager risk skoru / insight
> kartları **explicitly** kapsam dışı (saha datasıyla validate edilmeden
> yanlış-pozitif riski yüksek).

---

## Genel Mantık ve Kurallar

### Periyot semantiği

Mobil tarafta dört periyot chip'i vardır: `Today`, `Week`, `Month`, `Year`.
Mobile her istekte `?period=...&from=...&to=...` üçlüsünü birlikte gönderir;
backend bucket boyutunu `period`'a göre seçer:

| `period` | Kapsam (range)                 | Bucket | Örnek `from / to`                             |
| -------- | ------------------------------ | ------ | --------------------------------------------- |
| `TODAY`  | bugün 00:00 → bugün+1 00:00    | gün    | `2026-05-15T00:00:00Z / 2026-05-16T00:00:00Z` |
| `WEEK`   | bu hafta Pzt → bugün+1 00:00   | gün    | `2026-05-11T00:00:00Z / 2026-05-15T00:00:00Z` |
| `MONTH`  | ayın 1'i 00:00 → bugün+1 00:00 | gün    | `2026-05-01T00:00:00Z / 2026-05-15T00:00:00Z` |
| `YEAR`   | yılın 1 Oca'sı → bugün+1 00:00 | **ay** | `2026-01-01T00:00:00Z / 2026-05-15T00:00:00Z` |
| `CUSTOM` | (rezerv) — şu an UI'da yok     | gün    | (UI'da custom date picker eklenince)          |

Aynı `period` aynı UTC tarih çiftiyle birlikte geldiği için backend
range'i parse edip ek validation gerek duymadan kabul edebilir; period
yalnızca **bucket size** ve **karşılaştırma semantiği** seçimine yarar.

### KPI delta — clipped previous

Owner periyodun ortasındayken (örn. Çarşamba günü "Hafta") "geçen hafta'nın
tamamı"yla kıyaslamak yanıltıcı oluyor (her zaman -50% görünüyor). Bu
yüzden KPI delta için **clipped previous** kullanılır: geçen takvim
periyodunun **ilk N gününü** alıp current periyotla aynı uzunluğa kırparız.

```
N = elapsed days = range.length
previousFull = previous calendar period (full)
previousClip = previousFull[0 → N]      ← KPI delta bunu kullanır
delta = (current - previousClip) / previousClip × 100
```

Periyot tamamen kapanmışsa (Pazar gece) `previousClip == previousFull` —
clipping otomatik no-op olur.

| Şu an      | `period` | current range              | previousClip range   |
| ---------- | -------- | -------------------------- | -------------------- |
| Çar 14 May | `WEEK`   | Pzt 11 — Çar 14 (3g)       | Pzt 4 — Çar 7 (3g)   |
| 15 May     | `MONTH`  | 1 May — 15 May (15g)       | 1 Nis — 15 Nis (15g) |
| 3 May      | `YEAR`   | 1 Oca — 3 May 2026 (~123g) | 1 Oca — 3 May 2025   |

### Forecast — full previous

Forecast kartı _projeksiyon vs tam-period_ kıyaslar (clipped değil):
"Bu tempoda Mayıs sonu 165 000 сом → tam Nisan 147 000 сом'du, +12%".
Backend forecast endpoint'i `previousPeriodTotal` alanını **full
previousCalendar** üzerinden hesaplar.

### Today periyodunda comparison kapalı

`period=TODAY` geldiğinde mobile `compare=false` gönderir. Backend bu
durumda `previous` blokunu döndürmez (response'da `previous: null`). Aynı
şekilde Today için **forecast endpoint çağrılmaz** (mobile UI'da kart
gizlenir).

### Tek mekan zorunlu

MVP'de "tüm mekanlar" agregasyonu yok. `?venueId` her zaman set edilmiş
gelir (mobile ilk açılışta venue listesinden ilkini otomatik seçer).
Backend `venueId` boş gelirse `400 BAD_REQUEST` dönebilir veya owner'ın
ilk mekanını fallback alabilir — kullanıcı seçmeden istek gelmediği için
pratikte bu yol tetiklenmez.

### Para birimi

Tüm parasal alanlar **integer** (kuruş yok). `currency` enum'u
`facility` paketindeki `Currency` ile aynı: `KGS`, `USD`, `RUB`, `KZT`,
`TRY`. Mobile MVP tek mekan kapsamında çalıştığı için tek currency
yeterli; multi-currency düzgün handling v2'de.

---

## Authorization Roller

| Endpoint                      | OWNER | MANAGER |
| ----------------------------- | :---: | :-----: |
| GET `/api/v1/reports/venues`         |  ✅   |   ❌    |
| GET `/api/v1/reports/overview`       |  ✅   |   ❌    |
| GET `/api/v1/reports/revenue-series` |  ✅   |   ❌    |
| GET `/api/v1/reports/tables`         |  ✅   |   ❌    |
| GET `/api/v1/reports/tables/{id}`    |  ✅   |   ❌    |
| GET `/api/v1/reports/managers`       |  ✅   |   ❌    |
| GET `/api/v1/reports/managers/{id}`  |  ✅   |   ❌    |
| GET `/api/v1/reports/forecast`       |  ✅   |   ❌    |

Manager rolüyle bu uçların hiçbiri çağrılmaz; backend her zaman `403
FORBIDDEN` dönebilir.

---

## Ortak Query Parametreleri

`GET /reports/venues` hariç tüm uçlar aşağıdaki query parametrelerini
alır:

| Param     | Type    | Required | Notes                                                      |
| --------- | ------- | :------: | ---------------------------------------------------------- |
| `period`  | enum    |    ✅    | `TODAY` \| `WEEK` \| `MONTH` \| `YEAR` \| `CUSTOM`         |
| `from`    | ISO8601 |    ✅    | Inclusive UTC. `2026-05-01T00:00:00Z`                      |
| `to`      | ISO8601 |    ✅    | Exclusive UTC. `2026-05-15T00:00:00Z`                      |
| `venueId` | uuid    |    ✅    | Mobile her zaman gönderir; backend boşa düşmemeli          |
| `compare` | bool    |    ❌    | Default `true`. `false` ise `previous`/comparison döndürme |

> **Önemli:** `period`, `from` ve `to` **birlikte** gelir. Backend
> `from`/`to`'yu source of truth olarak alır; `period` yalnızca bucket
> size ve previous-period hesabı için (Mayıs vs Nisan gibi). Çelişen
> period+range gelirse backend `from`/`to`'yu kullanır, period'u uyarıya
> dönüştürmesin.

---

## Standart Error Yapısı

Diğer doclarla aynı zarfı kullanır (bkz. [auth-api.md](auth-api.md#error-response-formatı-öneri)):

```json
{
  "code": "REPORT_NOT_FOUND",
  "message": {
    "en": "Report data not found",
    "ru": "Данные отчёта не найдены",
    "ky": "Отчёт маалыматтары табылган жок"
  },
  "details": null
}
```

### Reports-spesifik error code'lar

| Backend `code`      | İstemci `ReportsErrorCode` | Tipik HTTP |
| ------------------- | -------------------------- | :--------: |
| `REPORT_NOT_FOUND`  | `notFound`                 |    404     |
| `FORBIDDEN`         | `forbidden`                |    403     |
| `NOT_ENOUGH_DATA`   | `notEnoughData`            |    422     |
| `VENUE_NOT_FOUND`   | `notFound`                 |    404     |
| `TABLE_NOT_FOUND`   | `notFound`                 |    404     |
| `MANAGER_NOT_FOUND` | `notFound`                 |    404     |
| (anything else)     | `unknown`                  |  4xx/5xx   |

---

## Endpoint'ler

### 1. GET `/api/v1/reports/venues`

Owner'a ait mekanların hafif listesi — Reports ekranındaki venue picker
bottom sheet'i bunu kullanır. Masa veya session bilgisi içermez.

#### Request

Query parametresi yok. Body yok.

#### 200 OK

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Merkez Şube",
    "number": 1
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "name": "Botanika",
    "number": 2
  }
]
```

| Field    | Type    | Notes                             |
| -------- | ------- | --------------------------------- |
| `id`     | uuid    | Venue ID                          |
| `name`   | string  | Görünen ad                        |
| `number` | integer | Owner içinde unique sıra numarası |

Boş array `[]` da geçerli — mobile bu durumda venue picker'ı render etmez
ve raporun diğer bölümleri "data yok" gösterir.

---

### 2. GET `/api/v1/reports/overview`

Üst seviye KPI özeti (gelir, oturum, iptal). MVP'de **sadece bu üç
alan**; ortalama süre / occupancy / aktif masa sayısı kasıtlı olarak
çıkarıldı.

#### Request

Query: ortak parametreler (`period`, `from`, `to`, `venueId`, `compare`).

#### 200 OK

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

| Field               | Type           | Notes                                                                                        |
| ------------------- | -------------- | -------------------------------------------------------------------------------------------- |
| `totalRevenue`      | integer        | sum(`session.totalAmount`) where status=COMPLETED, in range                                  |
| `totalSessions`     | integer        | count(\*) where status=COMPLETED                                                             |
| `cancelledSessions` | integer        | count(\*) where status=CANCELLED                                                             |
| `currency`          | enum           | `KGS`/`USD`/`RUB`/`KZT`/`TRY`                                                                |
| `previous`          | object \| null | Aynı şema, **clipped previous** range'i için (bkz. Genel Mantık §). `compare=false` ise null |

> **Compare semantiği:** `previous` bloğu **clipped previous** üzerinden
> hesaplanır. Yani current period 15 günse `previous` da geçen takvim
> periyodunun ilk 15 günüdür (full değil). `previous.previous` her
> zaman null — recursive değil.

Mobile delta hesabını client-side yapar (`(current - previous) / previous × 100`); backend yalnızca iki rakamı sağlar.

#### Errors

| HTTP | `code`            | Trigger                                   |
| ---- | ----------------- | ----------------------------------------- |
| 400  | `BAD_REQUEST`     | `from`/`to`/`venueId` eksik veya geçersiz |
| 401  | —                 | Token yok / expired                       |
| 403  | `FORBIDDEN`       | OWNER değil                               |
| 404  | `VENUE_NOT_FOUND` | `venueId` owner'a ait değil               |

---

### 3. GET `/api/v1/reports/revenue-series`

Günlük (ya da yıl periyodunda aylık) gelir noktaları — overview'daki bar
chart'ı besler.

#### Request

Query: ortak parametreler.

#### 200 OK

`period=MONTH`, range = (1 May, 16 May) için (15 günlük seri):

```json
[
  { "bucket": "2026-05-01T00:00:00Z", "revenue": 8400, "sessions": 22 },
  { "bucket": "2026-05-02T00:00:00Z", "revenue": 11200, "sessions": 28 },
  { "bucket": "2026-05-03T00:00:00Z", "revenue": 9100, "sessions": 24 },
  ...
]
```

`period=YEAR` için **aylık** bucket'lar:

```json
[
  { "bucket": "2026-01-01T00:00:00Z", "revenue": 245000, "sessions": 612 },
  { "bucket": "2026-02-01T00:00:00Z", "revenue": 268000, "sessions": 645 },
  { "bucket": "2026-03-01T00:00:00Z", "revenue": 281000, "sessions": 690 },
  { "bucket": "2026-04-01T00:00:00Z", "revenue": 247000, "sessions": 600 },
  { "bucket": "2026-05-01T00:00:00Z", "revenue": 142500, "sessions": 384 }
]
```

| Field      | Type    | Notes                                                                   |
| ---------- | ------- | ----------------------------------------------------------------------- |
| `bucket`   | ISO8601 | Bucket başlangıcı UTC. Yıl için ayın 1'i 00:00; diğerleri günün 00:00'ı |
| `revenue`  | integer | Bucket içindeki COMPLETED session toplam tutarı                         |
| `sessions` | integer | Bucket içindeki COMPLETED session sayısı                                |

> **Bucket coverage:** Backend `from`'dan `to`'ya kadar **her** bucket'ı
> dönmeli (boş günler/aylar `revenue=0, sessions=0` ile). Mobile chart'ta
> dolu olmasa bile zaman ekseni stable kalsın diye.

---

### 4. GET `/api/v1/reports/tables`

Seçili mekandaki **tüm** masaların gelir desc sıralı listesi. Top-N limiti
yok — owner'ın hangi masanın az kazandığını görmesi için tam liste lazım.

#### Request

Query: ortak parametreler.

#### 200 OK

```json
[
  {
    "tableId": "660e8400-e29b-41d4-a716-446655440001",
    "tableName": "VIP Salon",
    "tableNumber": 1,
    "venueId": "550e8400-e29b-41d4-a716-446655440000",
    "venueName": "Merkez Şube",
    "revenue": 45200,
    "sessions": 123,
    "currency": "KGS",
    "deltaPercent": 8
  },
  {
    "tableId": "660e8400-e29b-41d4-a716-446655440003",
    "tableName": null,
    "tableNumber": 2,
    "venueId": "550e8400-e29b-41d4-a716-446655440000",
    "venueName": "Merkez Şube",
    "revenue": 38400,
    "sessions": 102,
    "currency": "KGS",
    "deltaPercent": -3
  }
]
```

| Field          | Type            | Notes                                                                                |
| -------------- | --------------- | ------------------------------------------------------------------------------------ |
| `tableId`      | uuid            | Masa ID                                                                              |
| `tableName`    | string \| null  | Boş veya null olabilir (`Table 2` olarak gösterilir)                                 |
| `tableNumber`  | integer         | Venue içinde unique sıra numarası                                                    |
| `venueId`      | uuid            | Filter venue'su (her satırda aynı)                                                   |
| `venueName`    | string          | Venue adı (her satırda aynı)                                                         |
| `revenue`      | integer         | Bu masada COMPLETED session toplam tutarı                                            |
| `sessions`     | integer         | Bu masadaki COMPLETED session sayısı                                                 |
| `currency`     | enum            | Para birimi                                                                          |
| `deltaPercent` | integer \| null | **Clipped previous**'a göre yüzde değişim. `compare=false` veya yetersiz veride null |

Sıralama: `revenue DESC`. Bağ kopması için `tableNumber ASC`.

---

### 5. GET `/api/v1/reports/tables/{id}`

Tek masanın detay sayfası — overview KPI'larına ek olarak günlük/aylık
gelir trendi ve saat-gün heatmap'i içerir.

#### Path Params

- `id` (uuid) — masa ID'si.

#### Request

Query: ortak parametreler.

#### 200 OK

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
    { "bucket": "2026-05-01T00:00:00Z", "revenue": 1800, "sessions": 6 },
    { "bucket": "2026-05-02T00:00:00Z", "revenue": 2400, "sessions": 8 }
  ],
  "hourHeatmap": [
    [
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1200, 2800, 3100, 4500, 5200, 6800, 7100,
      8400, 9200, 7800, 5400, 2100, 0
    ],
    [
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1100, 2900, 3300, 4200, 4900, 6500, 7300,
      8100, 8800, 7200, 5100, 1900, 0
    ],
    "// ... 7 satır toplam (Pzt ... Paz)"
  ]
}
```

| Field           | Type           | Notes                                                                                                                                                                                                                                     |
| --------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `summary`       | TableReportRow | `/api/v1/reports/tables` listesindeki aynı satır şeması                                                                                                                                                                                          |
| `revenueSeries` | RevenuePoint[] | `/api/v1/reports/revenue-series` ile aynı bucket kuralı (period'a göre günlük/aylık), bu masaya scope'lu                                                                                                                                         |
| `hourHeatmap`   | int[7][24]     | 7 satır (ISO 8601 Pzt=index 0 … Paz=index 6) × 24 sütun (saat 0…23). Hücre değeri = o gün × o saat dilimine düşen COMPLETED session toplam revenue. `from`/`to` aralığındaki TÜM tarihlerde toplanır (period kullanıcının seçtiği aralık) |

Heatmap format kritik:

- **Sabit boyut:** her zaman `7 × 24` matris. Veri yoksa hücre = 0.
- **Weekday ordering:** ISO 8601 — Pazartesi=0, Salı=1, …, Pazar=6.
- **Hour ordering:** 0 = 00:00–01:00, 1 = 01:00–02:00, … 23 = 23:00–24:00 (yerel saatte; venue zone gerekirse v2'de).
- **Aggregation:** Aralıkta düşen tüm session'ların `startedAt` saatine göre bucket'lanır.

#### Errors

| HTTP | `code`            | Trigger                                  |
| ---- | ----------------- | ---------------------------------------- |
| 404  | `TABLE_NOT_FOUND` | id ile masa yok ya da farklı owner'a ait |
| 403  | `FORBIDDEN`       | OWNER değil                              |

---

### 6. GET `/api/v1/reports/managers`

Seçili mekanda gelir üretmiş manager'ların listesi (gelir desc).

#### Request

Query: ortak parametreler.

#### 200 OK

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
  },
  {
    "managerId": "user-102",
    "name": "Нурлан Беков",
    "username": "nurlan",
    "revenue": 64100,
    "sessions": 130,
    "cancelCount": 3,
    "currency": "KGS"
  }
]
```

| Field         | Type    | Notes                                                  |
| ------------- | ------- | ------------------------------------------------------ |
| `managerId`   | string  | User ID — `Session.managerId` ile eşleşir              |
| `name`        | string  | Görünen ad                                             |
| `username`    | string  | `@` olmadan; UI'da `@username` olarak                  |
| `revenue`     | integer | Bu manager'ın işlediği COMPLETED session toplam tutarı |
| `sessions`    | integer | Bu manager'ın COMPLETED session sayısı                 |
| `cancelCount` | integer | Bu manager'ın CANCELLED session sayısı (nötr veri)     |
| `currency`    | enum    | Para birimi                                            |

Sıralama: `revenue DESC`. **Risk skoru / fraud sinyalleri yok** — UI
sadece "kim ne kadar gelir üretti, kaç session iptal etti" gösterir.

> **Backend prerequisite:** `Session` kaydında `managerId` alanı bulunur
> (kim başlattı / bitirdi). Bkz. [session_api.md § Response Modelleri](session_api.md#response-modelleri)
> ve [home_page_api.md § Session](home_page_api.md#session).

---

### 7. GET `/api/v1/reports/managers/{id}`

Manager detay sayfası — KPI özeti + son ~40 session'ın log'u.

#### Path Params

- `id` (uuid) — manager (user) ID'si.

#### Request

Query: ortak parametreler.

#### 200 OK

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
      "customerName": "Asan",
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
      "customerName": null,
      "startedAt": "2026-05-14T17:05:00Z",
      "endedAt": "2026-05-14T17:05:42Z",
      "status": "CANCELLED",
      "currency": "KGS",
      "durationSeconds": null,
      "totalAmount": null,
      "cancelReason": "Yanlış başladım"
    }
  ]
}
```

| Field        | Type              | Notes                                              |
| ------------ | ----------------- | -------------------------------------------------- |
| `summary`    | ManagerReportRow  | `/api/v1/reports/managers` listesindeki aynı satır şeması |
| `sessionLog` | SessionLogEntry[] | En fazla **40** satır, `startedAt DESC`            |

**SessionLogEntry alanları:**

| Field             | Type            | COMPLETED              | CANCELLED          |
| ----------------- | --------------- | ---------------------- | ------------------ |
| `sessionId`       | uuid            | ✅                     | ✅                 |
| `tableId`         | uuid            | ✅                     | ✅                 |
| `tableName`       | string \| null  | ✅ (null olabilir)     | ✅ (null olabilir) |
| `tableNumber`     | integer         | ✅                     | ✅                 |
| `venueName`       | string          | ✅                     | ✅                 |
| `customerName`    | string \| null  | ✅ (null olabilir)     | ✅ (null olabilir) |
| `startedAt`       | ISO8601         | ✅                     | ✅                 |
| `endedAt`         | ISO8601         | ✅                     | ✅                 |
| `status`          | enum            | `COMPLETED`            | `CANCELLED`        |
| `currency`        | enum            | ✅                     | ✅                 |
| `durationSeconds` | integer \| null | ✅ (saniye)            | null               |
| `totalAmount`     | integer \| null | ✅ (kuruşsuz tam sayı) | null               |
| `cancelReason`    | string \| null  | null                   | ✅ (1-200 char)    |

> **`discountPercent` MVP'de YOK.** Fraud sinyali ile ilişkili olduğu
> için kasıtlı çıkarıldı. Saha datası + threshold kalibrasyonu sonrası
> v2'de geri eklenebilir.

#### Errors

| HTTP | `code`              | Trigger                                  |
| ---- | ------------------- | ---------------------------------------- |
| 404  | `MANAGER_NOT_FOUND` | id ile manager yok ya da farklı owner'da |
| 403  | `FORBIDDEN`         | OWNER değil                              |

---

### 8. GET `/api/v1/reports/forecast`

Mevcut tempoda **bu takvim periyodunun sonu** için projeksiyon. Sadece
overview kartının üstünde gösterilir.

#### Request

Query: ortak parametreler.

> Mobile `period=TODAY` durumunda bu endpoint'i **çağırmaz** (UI'da kart
> gizli). Backend yine de Today için anlamlı bir cevap dönmek zorunda
> değil — `404` veya `422 NOT_ENOUGH_DATA` da kabul edilir.

#### 200 OK

```json
{
  "points": [
    {
      "bucket": "2026-05-01T00:00:00Z",
      "expected": 8400,
      "lower": 8400,
      "upper": 8400,
      "isProjection": false
    },
    {
      "bucket": "2026-05-02T00:00:00Z",
      "expected": 11200,
      "lower": 11200,
      "upper": 11200,
      "isProjection": false
    },
    "// ... gerçek gözlem günleri (`isProjection: false`) ...",
    {
      "bucket": "2026-05-16T00:00:00Z",
      "expected": 9800,
      "lower": 8330,
      "upper": 11270,
      "isProjection": true
    },
    {
      "bucket": "2026-05-17T00:00:00Z",
      "expected": 10100,
      "lower": 8585,
      "upper": 11615,
      "isProjection": true
    }
  ],
  "projectedTotal": 165000,
  "previousPeriodTotal": 147000,
  "currency": "KGS"
}
```

| Field                 | Type            | Notes                                                                          |
| --------------------- | --------------- | ------------------------------------------------------------------------------ |
| `points`              | ForecastPoint[] | Geçmiş + projeksiyon birleşik seri. `isProjection=false` gerçek, `true` öngörü |
| `projectedTotal`      | integer         | Periyot sonu için **toplam beklenen gelir** (`realSoFar + projection`)         |
| `previousPeriodTotal` | integer         | **Full** previous calendar period toplam geliri (clipped değil)                |
| `currency`            | enum            | Para birimi                                                                    |

**ForecastPoint alanları:**

| Field          | Type    | Notes                                                            |
| -------------- | ------- | ---------------------------------------------------------------- |
| `bucket`       | ISO8601 | Bucket başlangıcı (revenueSeries ile aynı bucket kuralı)         |
| `expected`     | integer | Beklenen değer (geçmiş için gerçek revenue, gelecek için tahmin) |
| `lower`        | integer | Confidence band alt sınırı                                       |
| `upper`        | integer | Confidence band üst sınırı                                       |
| `isProjection` | boolean | `false` = geçmiş gerçek, `true` = projekte edilmiş gelecek       |

**Hesap önerisi (mobile mock'taki algoritma):**

1. Periyot başından bugüne kadar `revenueSeries` (gerçek) topla.
2. Linear regression slope + intercept hesapla.
3. Bugünden takvim periyot sonuna kadar (`projDays` gün) projeksiyon üret.
4. `projectedTotal = realSoFar + Σ projection`.
5. `previousPeriodTotal = full previousCalendar.totalRevenue` (KPI delta'sından farklı; **clipped değil**).
6. Confidence band: ±%15 (basit; v2'de regression standard error).

Backend bu algoritmayı **birebir** uygulamak zorunda değil; sadece üç
sözleşme garanti edilmeli:

- `projectedTotal` "periyot sonuna kadar toplam" semantiği taşır.
- `previousPeriodTotal` **full** previous calendar (Mayıs için tam Nisan).
- `points` listesi periyot başından **periyot sonuna** kadar dolu (geçmiş + projeksiyon birleşik).

#### Errors

| HTTP | `code`            | Trigger                                                     |
| ---- | ----------------- | ----------------------------------------------------------- |
| 422  | `NOT_ENOUGH_DATA` | < 7 günlük geçmiş varsa (forecast yapılamaz, kart gizlenir) |
| 404  | `VENUE_NOT_FOUND` | venue mevcut değil                                          |
| 403  | `FORBIDDEN`       | OWNER değil                                                 |

---

## Endpoint Özeti

| Method | Path                      | Cache | Rate Limit |
| ------ | ------------------------- | ----- | ---------- |
| GET    | `/api/v1/reports/venues`         | 60s   | 60/dk      |
| GET    | `/api/v1/reports/overview`       | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/revenue-series` | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/tables`         | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/tables/{id}`    | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/managers`       | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/managers/{id}`  | 5 dk  | 60/dk      |
| GET    | `/api/v1/reports/forecast`       | 15 dk | 30/dk      |

> Owner reports ekranını saniyede bir refresh etmeyecek — 5 dakikalık
> server-side cache pratikte yeterli ve veritabanı yükünü ciddi
> azaltır. Cache key: `(ownerId, venueId, period, from, to, compare)`.

---

## Backend Prerequisites

1. **`Session.managerId`** — kim başlattı/bitirdi alanı. `session_api.md`
   ve `home_page_api.md` Session şemalarında tanımlı. Manager raporları
   bunu kullanır.
2. **Session timestamp'leri server-side garanti** — `startedAt`/`endedAt`
   client'tan gelmiyor (zaten `session_api.md` bunu sağlıyor). Forecast
   ve clipping doğru çalışsın diye kritik.
3. **Soft delete farkındalığı** — silinmiş venue/table'lar geçmiş
   raporlarda görünmeli (audit). Mobile sadece aktif olanları picker'da
   gösterir, ama report sorguları geçmiş data'ya bakar.

---

## Açık Sorular / Karar Verilecekler

- [ ] `period=CUSTOM` mobile'da aktif değil. Backend bu enum değerini şu
      an yok sayıp `from`/`to`'yu kullanabilir mi? (Tavsiyem: evet,
      explicit `period` her zaman gönderilse bile range source-of-truth
      kalsın.)
- [ ] Forecast confidence interval — basit ±%15 yeter mi yoksa regression
      standard error mu? (MVP: ±%15 yeterli, v2'de iyileştirilir.)
- [ ] Heatmap timezone — venue local time mi UTC mi? UTC offset venue
      ayarlarında saklanmalı mı? (Tavsiyem: MVP'de owner'ın server
      timezone'unu kullan; v2'de venue per-zone.)
- [ ] Multi-currency owner (Bişkek + İstanbul gibi) — overview KPI'ları
      currency mix'te ne göstermeli? (MVP'de tek mekan kapsamı zaten tek
      currency seçtiriyor; multi-venue agregasyon v2'de.)
- [ ] Empty venue / table / manager listeleri — backend boş array `[]`
      mı yoksa `204` mu dönsün? (Tavsiyem: her zaman `200 []` —
      mobile'da empty state widget'ı render edilebilsin.)
