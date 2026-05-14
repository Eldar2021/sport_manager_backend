# Profile REST API — Backend Contract

Mevcut kullanıcının profil özetini döner. Login / refresh sonrası mobile tarafın
"profile bottom-sheet" widget'ı bu uca bağlanır.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Authorization:** `Authorization: Bearer <accessToken>` — zorunlu.

---

## Global headers

| Header            | Source                          | Example             | Required |
| ----------------- | ------------------------------- | ------------------- | -------- |
| `Accept-Language` | Cihaz dili (`en` / `ru` / `ky`) | `ru`                | Optional |
| `Authorization`   | `Bearer <accessToken>`          | `Bearer eyJhbGc...` | Required |

---

## Authorization Roller

| Endpoint              | OWNER | MANAGER |
| --------------------- | :---: | :-----: |
| GET `/api/v1/profile` |  ✅   |   ✅    |

> MANAGER da kendi profilini görür; ancak owner-spesifik agregeler
> (`venuesCount`, `managersCount`, `subscription`) **döndürülmez** —
> `profileData = null` gelir. Bkz. § Response — MANAGER.

---

## 1. GET `/api/v1/profile`

### Request

Body yok. Query yok.

### 200 OK — OWNER

```json
{
  "user": {
    "id": "5fb7e59c-422b-44af-b48a-71d42d6f00ac",
    "name": "test",
    "role": "OWNER",
    "email": "test@gmail.com",
    "phone": "+996 000 00 00 00"
  },
  "profileData": {
    "venuesCount": 2,
    "managersCount": 3,
    "subscription": {
      "status": "ACTIVE",
      "endDate": "2026-05-15T10:30:00.000Z",
      "daysUntilExpiry": 15,
      "graceDaysRemaining": 0
    }
  }
}
```

### 200 OK — MANAGER

```json
{
  "user": {
    "id": "9bb12e1d-...",
    "name": "Айбек",
    "role": "MANAGER",
    "email": "mgr@example.com",
    "phone": "+996 700 000 001"
  },
  "profileData": null
}
```

> Manager için `profileData` her zaman `null`. Mobile tarafta venue/manager
> count veya subscription bilgisi gösterilmez.

### Subscription özeti (OWNER-only)

| Field                | Type     | Notes                                                                    |
| -------------------- | -------- | ------------------------------------------------------------------------ |
| `status`             | enum     | `ACTIVE` / `GRACE` / `EXPIRED`. Anlık recompute edilir (cron beklenmez). |
| `endDate`            | ISO 8601 | Mevcut periyodun bitiş tarihi (UTC). Grace dahil değil.                  |
| `daysUntilExpiry`    | integer  | `floor((endDate − now) / 24h)`, en az 0.                                 |
| `graceDaysRemaining` | integer  | `status=GRACE` iken kalan grace günleri; diğer status'larda her zaman 0. |

> OWNER'ın hiç subscription kaydı yoksa (örn. yeni-kayıt sonrası TRIAL henüz
> oluşturulamadıysa) `profileData.subscription = null` döner. Mobile bunu
> "no subscription yet" durumu olarak ele alabilir.

`profileData.venuesCount` ve `profileData.managersCount` **soft-delete'leri
hariç tutar** (silinmiş kayıtlar sayılmaz).

### Errors

| HTTP | `code`               | Trigger                                                 |
| ---- | -------------------- | ------------------------------------------------------- |
| 400  | `UNAUTHORIZED`       | `Authorization` header yok                              |
| 400  | `INVALID_TOKEN`      | Token bozuk / imza geçersiz / revoke edilmiş            |
| 400  | `INVALID_TOKEN_TYPE` | Refresh token kullanıldı (filter access bekler)         |
| 401  | `SESSION_EXPIRED`    | **Yalnız** access token expired ise — diğer hatalar 400 |

> Detaylı status-code kuralı için bkz. [auth-api.md § Status code kuralı](auth-api.md#status-code-kuralı-401-yalnız-expired-için).

---

## Mobile için notlar

- Mobile login/register/refresh sonrası `GET /profile` çağırarak profile
  widget'ı doldurur. Cache'lenebilir; ama venue/manager/subscription
  state'i mutasyona uğradığında (yeni venue eklendi, sub yenilendi)
  refetch edilmesi önerilir.
- MANAGER'a göstermesin diye mobile'da `profileData` null kontrolüyle
  ilgili kartlar (venues count, subscription warning) gizlenir.
- `subscription.status` ekrandaki badge'i belirler:
  - `ACTIVE` + `daysUntilExpiry > expiryWarningDays` → normal
  - `ACTIVE` + `daysUntilExpiry ≤ expiryWarningDays` → uyarı badge'i
  - `GRACE` → "grace" uyarısı + `graceDaysRemaining` countdown
  - `EXPIRED` → CTA "subscribe now"

---

## İlişkili endpoint'ler

- [auth-api.md](auth-api.md) — login / refresh ile token alındıktan sonra profile çağrılır
- [subscription-api.md](subscription-api.md) — tam subscription detayı + payment history
- [managers-api.md](managers-api.md) — manager listesi yönetimi
- [home_page_api.md](home_page_api.md) — venue + table CRUD
