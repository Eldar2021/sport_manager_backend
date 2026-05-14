# Home Page API

После login или register пользователь сразу попадает на главную страницу (Home Page). Home показывает выбранное заведение и его столы.

**Base URL:** `<BASE_URL>` (на мобильной стороне передаётся через `--dart-define=BASE_URL=...`)
**Content-Type:** `application/json; charset=utf-8`

---

## Глобальные заголовки (для каждого запроса)

| Header            | Источник                                                          | Пример              | Обязательный |
| ----------------- | ----------------------------------------------------------------- | ------------------- | ------------ |
| `Accept-Language` | Язык устройства (`en` / `ru` / `ky`)                              | `ru`                | Опциональный |
| `versionBuild`    | Build number приложения                                           | `42`                | Опциональный |
| `os`              | Платформа                                                         | `ios` / `android`   | Опциональный |
| `Authorization`   | `Bearer <accessToken>` — только для аутентифицированных эндпоинтов | `Bearer eyJhbGc...` | По ситуации  |

> **Примечание:** если `Accept-Language` установлен, сообщения об ошибках могут возвращаться только на этом языке (для уменьшения размера ответа). Если клиент его не передал — по умолчанию возвращаются сразу `en`+`ru`+`ky`.

---

## Роли авторизации

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

Доступ без прав → **403 FORBIDDEN**.

---

## Стандартный формат ошибки

Все ответы с ошибками отдаются в следующем формате:

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

Для валидационных ошибок поле `details` заполняется:

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

### Список кодов ошибок

| Code                       | HTTP | Значение                                                                                                                                          |
| -------------------------- | :--: | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VALIDATION_ERROR`         | 422  | Валидация тела не прошла                                                                                                                          |
| `BAD_REQUEST`              | 400  | Некорректный запрос                                                                                                                                |
| `UNAUTHORIZED`             | 401  | Нет токена / невалиден / истёк                                                                                                                    |
| `FORBIDDEN`                | 403  | Нет прав на операцию                                                                                                                              |
| `VENUE_NOT_FOUND`          | 404  | Заведение не найдено                                                                                                                              |
| `TABLE_NOT_FOUND`          | 404  | Стол не найден                                                                                                                                    |
| `VENUE_NUMBER_TAKEN`       | 409  | Такой номер заведения уже используется                                                                                                            |
| `TABLE_NUMBER_TAKEN`       | 409  | Такой номер стола уже используется в этом заведении                                                                                               |
| `VENUE_HAS_TABLES`         | 409  | Внутри заведения есть столы — сначала удалите их                                                                                                  |
| `TABLE_HAS_ACTIVE_SESSION` | 409  | У стола есть активная сессия, удалить нельзя                                                                                                      |
| `SUBSCRIPTION_REQUIRED`    | 403  | Подписка владельца `EXPIRED` или `GRACE@0` (write-gate; см. [subscription-api.md](subscription-api.md#subscription-gate--влияние-на-другие-эндпоинты)) |
| `INTERNAL_SERVER_ERROR`    | 500  | Непредвиденная ошибка сервера                                                                                                                     |
| `SERVICE_UNAVAILABLE`      | 503  | Сервис временно недоступен                                                                                                                        |

---

## Domain-модели

### Venue

```ts
{
  id: string (uuid),
  name: string (1-100 chars),
  number: integer (>= 1),     // уникален в рамках владельца
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
  number: integer (>= 1),     // уникален в рамках заведения
  description: string | null (0-500 chars),
  tarifAmount: integer (1-1000000), // целое число, без копеек
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
  managerId: string (uuid),       // пользователь, запустивший сессию (owner или manager)
  isActive: boolean,
  isPaused: boolean,
  startedAt: string (ISO 8601),
  pausedAt: string (ISO 8601) | null,
  resumedAt: string (ISO 8601) | null,
  totalPausedSeconds: integer,    // суммарная длительность пауз в этой сессии
  tarifAmountSnapshot: integer,   // цена стола на момент старта сессии
  tarifTypeSnapshot: "MINUTE" | "HOUR" | "DAY"
}
```

> **Правило снимка (snapshot):** При старте сессии цена стола копируется. Если владелец меняет цену в середине сессии — текущая сессия не затрагивается.
>
> **`managerId`:** ID пользователя, запустившего сессию. Если стартовал владелец — его ID; если менеджер — ID менеджера. Этот ID использует модуль отчётов (производительность менеджеров / сигналы фрода). См. [reports-api.md](reports-api.md).

### SelectedVenueResponse

Структура, возвращаемая эндпоинтами `GET /venue/selected` и `PATCH /venue/selected`:

```ts
{
  venue: Venue,
  tables: Table[]
}
```

---

## Эндпоинты

### 1. Получить список заведений

Вызывается при открытии селектора заведений (bottom sheet). Возвращает все доступные пользователю заведения. Лёгкий ответ — без столов и без информации о сессиях (для производительности).

**Endpoint:**

```
GET /api/v1/venue/list
```

**Headers:** `Authorization: Bearer <accessToken>`

**Ответ — успех (200):**

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

**Ответ — заведений нет (200):**

```json
[]
```

> Если приходит пустой массив, мобильный клиент показывает CTA "Create Venue".

**Примечания:**

- Для OWNER: все принадлежащие ему заведения.
- Для MANAGER: все заведения владельца, к которому он привязан.
- В списке всегда ровно одно `selected: true` (если есть хотя бы одно заведение).
- Сортировка: `number` по возрастанию, при равенстве — `createdAt` по возрастанию.

---

### 2. Получить выбранное заведение

Вызывается при открытии главного экрана. Возвращает выбранное заведение пользователя + его столы.

**Endpoint:**

```
GET /api/v1/venue/selected
```

**Headers:** `Authorization: Bearer <accessToken>`

**Ответ — заведений нет (404):**

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

→ Получив эту ошибку, мобильный клиент перенаправляет на экран "Create Venue".

**Ответ — успех (200):**

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

**Примечания:**

- `tables` может быть пустым массивом → мобильный клиент перенаправляет на экран создания стола.
- Если пользователь не выбирал заведение вручную, по умолчанию выбирается самое старое (по `createdAt`), и состояние сохраняется в БД.

---

### 3. Изменить выбранное заведение

Когда пользователь через селектор (bottom sheet) переключается на другое заведение. Возвращаются полные данные нового выбранного заведения (с таблицами) — так клиенту не нужен дополнительный запрос для обновления Home.

**Endpoint:**

```
PATCH /api/v1/venue/selected
```

**Headers:** `Authorization: Bearer <accessToken>`

**Тело:**

```json
{
  "venueId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Ответ (200):**

Та же структура, что у `GET /venue/selected` — поля `venue` и `tables`:

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
    // ... столы этого заведения
  ]
}
```

**Ошибки:**

- `404 VENUE_NOT_FOUND` — заведение не найдено или не принадлежит пользователю
- `403 FORBIDDEN` — это заведение другого владельца

---

### 4. Создать заведение

**Endpoint:**

```
POST /api/v1/venue/create
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Тело:**

```json
{
  "name": "Merkez Şube",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek"
}
```

**Валидация:**
| Поле    | Тип     | Обязательное | Правила                            |
| ------- | ------- | :----------: | ---------------------------------- |
| name    | string  |      ✅      | 1-100 символов                     |
| number  | integer |      ✅      | >= 1, уникален в рамках владельца  |
| address | string  |      ❌      | 0-255 символов (может быть null)   |

**Ответ (201):**

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

**Примечания:**

- Первое созданное заведение автоматически `selected: true`.
- Последующие заведения создаются с `selected: false`.

**Ошибки:**

- `422 VALIDATION_ERROR`
- `409 VENUE_NUMBER_TAKEN` — этот номер уже занят
- `403 FORBIDDEN` — менеджер не может вызывать этот эндпоинт
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 5. Обновить заведение

**Endpoint:**

```
PUT /api/v1/venue/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Path Params:**

- `id` (uuid) — ID заведения

**Тело:**

```json
{
  "name": "Merkez Şube (Güncellenmiş)",
  "number": 1,
  "address": "Chui Avenue 132, Bishkek"
}
```

> **Примечание:** `id` в теле не передаётся, берётся из URL.

**Ответ (200):**

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

**Ошибки:**

- `404 VENUE_NOT_FOUND`
- `409 VENUE_NUMBER_TAKEN` — новый номер уже занят другим заведением
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 6. Удалить заведение

**Endpoint:**

```
DELETE /api/v1/venue/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Ответ (200):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleted": true
}
```

**Примечания:**

- Применяется **мягкое удаление (soft delete)** (`deletedAt` устанавливается, запись физически не удаляется). Критично для исторических отчётов.
- Столы удалённого заведения тоже soft delete (каскадно).
- Если удаляемое заведение `selected: true` и у пользователя есть другие — автоматически выбирается самое старое.
- Если хоть у одного стола есть активная сессия — удалить **нельзя** → `409 TABLE_HAS_ACTIVE_SESSION`.

**Ошибки:**

- `404 VENUE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION` — у одного из столов внутри есть активная сессия
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 7. Создать стол

**Endpoint:**

```
POST /api/v1/table/create
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Тело:**

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

**Валидация:**
| Поле        | Тип     | Обязательное | Правила                                  |
| ----------- | ------- | :----------: | ---------------------------------------- |
| venueId     | uuid    |      ✅      | Заведение принадлежит владельцу          |
| name        | string  |      ❌      | 0-100 символов                           |
| number      | integer |      ✅      | >= 1, уникален в рамках заведения        |
| description | string  |      ❌      | 0-500 символов                           |
| tarifAmount | integer |      ✅      | 1-1.000.000 (целое, без дробной части)   |
| currency    | enum    |      ✅      | `KGS`, `USD`, `RUB`, `KZT`, `TRY`        |
| tarifType   | enum    |      ✅      | `MINUTE`, `HOUR`, `DAY`                  |

**Ответ (201):**

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

**Ошибки:**

- `404 VENUE_NOT_FOUND`
- `409 TABLE_NUMBER_TAKEN` — в этом заведении уже есть стол с таким номером
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 8. Обновить стол

**Endpoint:**

```
PUT /api/v1/table/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Path Params:**

- `id` (uuid) — ID стола

**Тело:**

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

> **Примечание:** `id` и `venueId` берутся из URL, в теле их нет. Перенос стола в другое заведение не поддерживается (вне scope MVP).

**Ответ (200):**

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

**Важно:** Если при активной сессии меняется `tarifAmount` или `tarifType` — **текущая сессия не затрагивается** (используется snapshot). Новые сессии стартуют по новой цене.

**Ошибки:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_NUMBER_TAKEN`
- `422 VALIDATION_ERROR`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 9. Удалить стол

**Endpoint:**

```
DELETE /api/v1/table/{id}
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Ответ (200):**

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "deleted": true
}
```

**Примечания:**

- Soft delete.
- Если есть активная сессия — удалить нельзя.

**Ошибки:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

## Сводка по эндпоинтам

| Method | Path                     | Auth     | Rate Limit |
| ------ | ------------------------ | -------- | ---------- |
| GET    | `/api/v1/venue/list`     | Required | 60/минуту  |
| GET    | `/api/v1/venue/selected` | Required | 60/минуту  |
| PATCH  | `/api/v1/venue/selected` | Required | 30/минуту  |
| POST   | `/api/v1/venue/create`   | Owner    | 10/минуту  |
| PUT    | `/api/v1/venue/{id}`     | Owner    | 30/минуту  |
| DELETE | `/api/v1/venue/{id}`     | Owner    | 10/минуту  |
| POST   | `/api/v1/table/create`   | Owner    | 30/минуту  |
| PUT    | `/api/v1/table/{id}`     | Owner    | 60/минуту  |
| DELETE | `/api/v1/table/{id}`     | Owner    | 30/минуту  |

---

## Открытые вопросы / решения

- [ ] Возвращать ошибки на одном языке (по `Accept-Language`) или всегда на трёх? (Рекомендация: multi-language — просто и гибко для клиента.)
- [ ] Список валют фиксирован или приходит из config-эндпоинта? (Рекомендация: в MVP захардкодить 5 валют, в v2 добавить config endpoint.)
- [ ] Должен ли менеджер видеть venue/table в режиме read-only? (Рекомендация: да, менеджер и так видит столы на Home.)
- [ ] Нужен ли `tarifType: DAY` в MVP? Большинство залов работает почасово. (Рекомендация: HOUR и MINUTE достаточно, DAY — в v2.)
- [ ] При удалении заведения с активной сессией — добавлять ли параметр "force delete"? (Рекомендация: нет, владелец сначала должен завершить сессии.)
