# Managers REST API — Backend Contract

Owner-tarafından yönetilen manager listesi. Mobil istemci (`packages/managers`) bu uçları kullanır.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Auth:** Tüm uçlar `Authorization: Bearer <accessToken>` ister, **role = `OWNER`**. Owner olmayan kullanıcı `403` almalı.

> Invite kod (gösterilmek için) ayrı bir uca ait: `POST /api/v1/auth/invite-code` (bkz. [auth-api.md](auth-api.md#6-post-apiv1authinvite-code)). Managers ekranı bu mevcut ucu yeniden kullanır.
>
> Invite kodun **rotation / yenileme** uç noktası şu an için yok — istemcide "yenile" butonu da yoktur. Eklenirse `POST /api/v1/auth/invite-code/refresh` öneriyoruz.

---

## 1. GET `/api/v1/managers`

Owner'a bağlı manager listesi.

### Request

Query parametresi yok. Body yok.

### 200 OK — response body

```json
[
  {
    "id": "user-101",
    "name": "Айбек Асанов",
    "username": "aibek",
    "lastSeenAt": "2026-04-30T08:15:00Z"
  },
  {
    "id": "user-102",
    "name": "Нурлан Беков",
    "username": "nurlan",
    "lastSeenAt": "2026-04-28T19:42:00Z"
  },
  {
    "id": "user-103",
    "name": "Данияр Токтогул",
    "username": "daniyar",
    "lastSeenAt": null
  }
]
```

### Errors

| HTTP | `code`      | Trigger               |
| ---- | ----------- | --------------------- |
| 401  | —           | Token yok / expired   |
| 403  | `FORBIDDEN` | Kullanıcı OWNER değil |

---

## 2. DELETE `/api/v1/managers/{id}`

Belirtilen manager hesabını owner'ın takımından çıkarır. Backend tarafında manager'ın aktif sessionları varsa nasıl davranacağı backend kararı (kapatılabilir veya `409` dönebilir).

### Request

Body yok.

### 200 OK / 204 No Content

Body beklenmiyor.

### Errors

| HTTP | `code`               | Trigger                                             |
| ---- | -------------------- | --------------------------------------------------- |
| 401  | —                    | Token yok / expired                                 |
| 403  | `FORBIDDEN`          | Kullanıcı OWNER değil veya başka owner'ın manager'ı |
| 404  | `MANAGER_NOT_FOUND`  | Bu id ile manager yok                               |
| 409  | `HAS_ACTIVE_SESSION` | Manager'ın aktif session'ı var (opsiyonel)          |

---

## Models — Alan referansı

### `ManagerModel`

```json
{
  "id": "user-101",
  "name": "Айбек Асанов",
  "username": "aibek",
  "lastSeenAt": "2026-04-30T08:15:00Z"
}
```

| Field        | Type             | Required | Notes                                            |
| ------------ | ---------------- | -------- | ------------------------------------------------ |
| `id`         | string           | yes      | Server-generated, stable                         |
| `name`       | string           | yes      | Görünen ad                                       |
| `username`   | string           | yes      | `@` olmadan; UI'da `@username` olarak gösterilir |
| `lastSeenAt` | ISO-8601 \| null | no       | Son aktif zaman; null → "henüz görülmedi"        |

---

## Error response formatı

`auth-api.md` ile aynı zarf:

```json
{
  "code": "MANAGER_NOT_FOUND",
  "message": {
    "en": "Manager not found",
    "ru": "Менеджер не найден",
    "ky": "Менеджер табылган жок"
  },
  "details": null
}
```

İstemcideki `ManagerErrorCode` enum'una göre backend code map'i:

| Backend `code`       | İstemci `ManagerErrorCode` | Tipik HTTP |
| -------------------- | -------------------------- | ---------- |
| `MANAGER_NOT_FOUND`  | `notFound`                 | 404        |
| `FORBIDDEN`          | `forbidden`                | 403        |
| `HAS_ACTIVE_SESSION` | `hasActiveSession`         | 409        |
| (anything else)      | `unknown`                  | 4xx/5xx    |
