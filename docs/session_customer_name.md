# Session — `customerName` spec

Session başlatılırken müşterinin adını opsiyonel olarak saklamak için kullanılan
alan. Masa numarası yerine müşteri adıyla "kim oturuyor" sorusuna cevap vermek
ve raporlarda audit izi tutmak için eklendi.

Üst seviye kurallar [`session_api.md`](session_api.md) ve [`reports-api.md`](reports-api.md)
içinde tekrar edilir. Bu doc, alanın bütün davranışını **tek yerde** toplar.

---

## 1. Amaç

- **UX:** Manager telefonunda masa kartında "Masa 3 — Asan" göstererek müşteriyi
  hızlıca eşleştirir; tek başına `tableNumber` üç parti aynı saatte oynarken yetersiz kalıyordu.
- **Audit:** Owner [`/api/v1/reports/managers/{id}`](reports-api.md#7-get-apiv1reportsmanagersid)
  altındaki `sessionLog[]`'da manager'ın ay boyunca hangi müşterileri işlediğini görür.

---

## 2. Yaşam döngüsü ve immutability

```
start (body.customerName)
   │
   │ normalize → trim, boşsa NULL
   │ validate → uzunluk > 80 ise 422 INVALID_CUSTOMER_NAME
   │
   ▼
Session.customerName  ←──── donmuş snapshot
   │
   │ pause / resume / finish / cancel:
   │   request body'sinde customerName field'ı YOK
   │   (DTO'da hiç tanımlanmamış)  → istemci gönderse bile parse edilmez
   │
   ▼
response.customerName  (her durumda aynı değer)
```

- **Sadece `start`'ta yazılır.** Sonraki çağrılarda hiçbir koşulda değişmez.
- `pause` / `resume` / `finish` / `cancel` DTO'larında `customerName` alanı
  **bilinçli olarak tanımlanmadı** — Jackson unknown-field davranışıyla yok sayılır,
  manager isim değiştirip raporu manipüle edemez. (Bkz. [session_api.md § Kritik Kurallar](session_api.md#kritik-kurallar) madde 8.)

---

## 3. Normalize ve validate

Sunucu sırasıyla:

1. **Trim** — başta/sonda whitespace temizlenir.
2. **Boş kontrolü** — trim sonrası uzunluk 0 ise `customerName = NULL` olarak saklanır
   (hata dönülmez, çünkü alan opsiyonel).
3. **Uzunluk kontrolü** — trim sonrası uzunluk > 80 ise:

   ```json
   HTTP 422
   {
     "code": "INVALID_CUSTOMER_NAME",
     "message": {
       "en": "Customer name must be at most 80 characters",
       "ru": "Имя клиента не должно превышать 80 символов",
       "ky": "Кардардын аты 80 белгиден ашпашы керек"
     },
     "details": null
   }
   ```

> **Karakter sayımı:** Java `String.length()` (UTF-16 code unit). Emoji dahil
> uzun stringler limit içinde kalır; gerçek mobil kullanım için fazlasıyla yeterli.
> Daha sıkı bir kural (örn. graphme cluster) gerekirse ayrı issue açılır.

DB kolonu: `VARCHAR(80) NULL` — `length=80`'lık schema constraint normalize sonrası
limit ile birebir aynı, defense-in-depth.

---

## 4. Etkilenen response şemaları

`customerName: string | null` alanı şu yapılarda döner:

| Yapı                          | Endpoint(ler)i                                              |
| ----------------------------- | ----------------------------------------------------------- |
| `SessionLiteResponse`         | `POST /api/v1/session/start`, `/pause`, `/resume`           |
| `SessionResultResponse`       | `POST /api/v1/session/{id}/finish`, `/cancel`               |
| `SessionResponse` (home card) | `GET /api/v1/venue/selected` içindeki masa.session bloğu    |
| `SessionLogEntryResponse`     | `GET /api/v1/reports/managers/{id}` içindeki `sessionLog[]` |

`pause` / `resume` / `finish` / `cancel` response'larında alan görünür ama değer
her zaman `start` anında yazılan değerdir (veya `NULL`).

---

## 5. Reports davranışı

- `sessionLog[]` `startedAt DESC` sıralı; `customerName` ham veriyle döner —
  raporda gizleme / maskeleme yok.
- Owner filtre/arama yapmak isterse mobile tarafta liste üzerinde local arama
  yapar (MVP'de server-side `?customer=...` filtresi yok).
- Heatmap, revenue series, manager KPI'ları `customerName`'den **etkilenmez**;
  bu alan yalnızca log satırlarında görünür.

---

## 6. Geri uyumluluk

- Mevcut session satırları için backfill **yapılmaz**. 7 aylık tarihsel veride
  `customer_name = NULL` kalır — audit anlamı bozulmasın diye sentetik isim
  yazmıyoruz.
- Eski mobile client'lar `customerName`'i `start` body'sinde göndermez ⇒
  alan `NULL` olarak saklanır, ek davranış değişikliği olmaz.
- Eski mobile client'lar response'ta yeni alanı görür ama Jackson tarafında
  tanımlamadıkları için ignore ederler — kırıcı değişiklik yok.

---

## 7. Migration

Flyway `V4__session_customer_name.sql`:

```sql
ALTER TABLE sessions
  ADD COLUMN customer_name VARCHAR(80);
```

Index gerekmez — `customerName` üzerinden sorgu yok.

---

## 8. Hata kodu özeti

| Code                    | HTTP | Tetikleyici                                     |
| ----------------------- | :--: | ----------------------------------------------- |
| `INVALID_CUSTOMER_NAME` | 422  | `customerName` trim sonrası 80 karakteri aşıyor |

Diğer session error code'ları aynen geçerli — `customerName` boş veya null
gelmesi tek başına hata sebebi değildir.
