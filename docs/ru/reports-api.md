# Reports REST API — Контракт бэкенда

Эндпоинты, которые наполняют экран отчётов / бизнес-аналитики, доступный владельцу. Мобильный клиент (`packages/reports`) использует эти эндпоинты.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Auth:** Все эндпоинты требуют `Authorization: Bearer <accessToken>`, **role = `OWNER`**.
Не-OWNER должен получить `403`.

> Reports — экран, дающий владельцу чёткий ответ на вопросы: "откуда приходят деньги, как работают столы/менеджеры, что меня ждёт к концу месяца/года при таком темпе". **MVP scope** намеренно узкий: только KPI **выручки и количества сессий**, фильтр по календарным датам, охват — одно заведение. Сигналы фрода / risk-скор менеджера / insight-карточки **явно вне scope** (без валидации на боевых данных риск false-positive слишком высок).

---

## Общая логика и правила

### Семантика периода

На мобильном клиенте есть четыре чипа периода: `Today`, `Week`, `Month`, `Year`. Клиент в каждом запросе передаёт тройку `?period=...&from=...&to=...`; бэкенд по `period` выбирает размер bucket'а:

| `period` | Охват (range)                       | Bucket  | Пример `from / to`                                |
| -------- | ----------------------------------- | ------- | ------------------------------------------------- |
| `TODAY`  | сегодня 00:00 → сегодня+1 00:00     | день    | `2026-05-15T00:00:00Z / 2026-05-16T00:00:00Z`     |
| `WEEK`   | этой недели Пн → сегодня+1 00:00    | день    | `2026-05-11T00:00:00Z / 2026-05-15T00:00:00Z`     |
| `MONTH`  | 1-е число месяца 00:00 → сегодня+1  | день    | `2026-05-01T00:00:00Z / 2026-05-15T00:00:00Z`     |
| `YEAR`   | 1 января 00:00 → сегодня+1 00:00    | **месяц** | `2026-01-01T00:00:00Z / 2026-05-15T00:00:00Z`     |
| `CUSTOM` | (резерв) — сейчас в UI отсутствует  | день    | (когда добавится custom date picker в UI)         |

Поскольку `period` всегда приходит вместе с UTC-парой дат, бэкенд может принять диапазон без дополнительной валидации; `period` нужен только для **размера bucket'а** и **семантики сравнения**.

### KPI delta — clipped previous

Когда владелец находится в середине периода (например, в среду на "Неделе"), сравнение с "прошлой неделей целиком" вводит в заблуждение (всегда показывает -50%). Поэтому для KPI delta используется **clipped previous**: первые N дней прошлого календарного периода обрезаются до длины текущего.

```
N = elapsed days = range.length
previousFull = предыдущий календарный период (полный)
previousClip = previousFull[0 → N]      ← KPI delta использует это
delta = (current - previousClip) / previousClip × 100
```

Если период полностью закрыт (вечер воскресенья), `previousClip == previousFull` — клиппинг автоматически становится no-op.

| Сейчас        | `period` | current range              | previousClip range   |
| ------------- | -------- | -------------------------- | -------------------- |
| Ср 14 мая     | `WEEK`   | Пн 11 — Ср 14 (3д)         | Пн 4 — Ср 7 (3д)     |
| 15 мая        | `MONTH`  | 1 мая — 15 мая (15д)       | 1 апр — 15 апр (15д) |
| 3 мая         | `YEAR`   | 1 янв — 3 мая 2026 (~123д) | 1 янв — 3 мая 2025   |

### Forecast — full previous

Карточка forecast сравнивает _проекцию vs полный период_ (не clipped): "При таком темпе в мае ожидается 165 000 сом → в апреле было 147 000 сом, +12%". Эндпоинт forecast рассчитывает `previousPeriodTotal` по **full previousCalendar**.

### В Today сравнение отключено

При `period=TODAY` клиент передаёт `compare=false`. В этом случае бэкенд не возвращает блок `previous` (в ответе `previous: null`). Аналогично для Today **эндпоинт forecast не вызывается** (карточка в UI скрыта).

### Одно заведение обязательно

В MVP агрегации по "всем заведениям" нет. `?venueId` всегда передаётся (клиент при первом открытии автоматически выбирает первое заведение из списка). Если `venueId` пустой — бэкенд может вернуть `400 BAD_REQUEST` либо взять первое заведение владельца как fallback — на практике запрос без выбранного заведения не приходит.

### Валюта

Все денежные поля — **integer** (без копеек). Enum `currency` совпадает с `Currency` пакета `facility`: `KGS`, `USD`, `RUB`, `KZT`, `TRY`. Поскольку MVP работает в рамках одного заведения, достаточно одной валюты; нормальная поддержка multi-currency — в v2.

---

## Роли авторизации

| Endpoint                             | OWNER | MANAGER |
| ------------------------------------ | :---: | :-----: |
| GET `/api/v1/reports/venues`         |  ✅   |   ❌    |
| GET `/api/v1/reports/overview`       |  ✅   |   ❌    |
| GET `/api/v1/reports/revenue-series` |  ✅   |   ❌    |
| GET `/api/v1/reports/tables`         |  ✅   |   ❌    |
| GET `/api/v1/reports/tables/{id}`    |  ✅   |   ❌    |
| GET `/api/v1/reports/managers`       |  ✅   |   ❌    |
| GET `/api/v1/reports/managers/{id}`  |  ✅   |   ❌    |
| GET `/api/v1/reports/forecast`       |  ✅   |   ❌    |

С ролью manager ни один из этих эндпоинтов не вызывается; бэкенд может всегда возвращать `403 FORBIDDEN`.

---

## Общие query-параметры

Все эндпоинты, кроме `GET /api/v1/reports/venues`, принимают следующие query-параметры:

| Param     | Тип     | Обязательный | Примечания                                                  |
| --------- | ------- | :----------: | ----------------------------------------------------------- |
| `period`  | enum    |      ✅      | `TODAY` \| `WEEK` \| `MONTH` \| `YEAR` \| `CUSTOM`          |
| `from`    | ISO8601 |      ✅      | Inclusive UTC. `2026-05-01T00:00:00Z`                       |
| `to`      | ISO8601 |      ✅      | Exclusive UTC. `2026-05-15T00:00:00Z`                       |
| `venueId` | uuid    |      ✅      | Клиент передаёт всегда; бэкенд не должен оставаться с пустым |
| `compare` | bool    |      ❌      | По умолч. `true`. При `false` блок `previous`/comparison не возвращается |

> **Важно:** `period`, `from` и `to` приходят **вместе**. Бэкенд берёт `from`/`to` как источник истины; `period` нужен только для размера bucket'а и расчёта предыдущего периода (Май vs Апрель и т.д.). При противоречии period+range бэкенд использует `from`/`to`, period не превращается в warning.

---

## Стандартный формат ошибки

Используется тот же конверт, что и в других doc'ах (см. [auth-api.md](auth-api.md#формат-ответа-с-ошибкой-рекомендация)):

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

### Специфичные для reports коды ошибок

| `code` бэкенда      | `ReportsErrorCode` клиента | Типичный HTTP |
| ------------------- | -------------------------- | :-----------: |
| `REPORT_NOT_FOUND`  | `notFound`                 |     404       |
| `FORBIDDEN`         | `forbidden`                |     403       |
| `NOT_ENOUGH_DATA`   | `notEnoughData`            |     422       |
| `VENUE_NOT_FOUND`   | `notFound`                 |     404       |
| `TABLE_NOT_FOUND`   | `notFound`                 |     404       |
| `MANAGER_NOT_FOUND` | `notFound`                 |     404       |
| (всё остальное)     | `unknown`                  |   4xx/5xx     |

---

## Эндпоинты

### 1. GET `/api/v1/reports/venues`

Лёгкий список заведений владельца — для bottom sheet венью-пикера на экране Reports. Без столов и сессий.

#### Запрос

Query-параметры отсутствуют. Тело отсутствует.

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

| Поле     | Тип     | Примечания                              |
| -------- | ------- | --------------------------------------- |
| `id`     | uuid    | ID заведения                            |
| `name`   | string  | Отображаемое имя                        |
| `number` | integer | Уникальный порядковый номер у владельца |

Пустой массив `[]` тоже валиден — клиент в этом случае не рендерит венью-пикер и в остальных секциях отчёта показывает "нет данных".

---

### 2. GET `/api/v1/reports/overview`

Верхнеуровневые KPI (выручка, сессии, отмены). В MVP **только эти три поля**; средняя длительность / occupancy / число активных столов намеренно исключены.

#### Запрос

Query: общие параметры (`period`, `from`, `to`, `venueId`, `compare`).

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

| Поле                | Тип            | Примечания                                                                                       |
| ------------------- | -------------- | ------------------------------------------------------------------------------------------------ |
| `totalRevenue`      | integer        | sum(`session.totalAmount`) где status=COMPLETED, в диапазоне                                     |
| `totalSessions`     | integer        | count(\*) где status=COMPLETED                                                                   |
| `cancelledSessions` | integer        | count(\*) где status=CANCELLED                                                                   |
| `currency`          | enum           | `KGS`/`USD`/`RUB`/`KZT`/`TRY`                                                                    |
| `previous`          | object \| null | Та же схема, для **clipped previous** диапазона (см. Общая логика §). При `compare=false` — null |

> **Семантика compare:** Блок `previous` рассчитывается по **clipped previous**. То есть если текущий период 15 дней, то `previous` — это первые 15 дней предыдущего календарного периода (не full). `previous.previous` всегда null — не рекурсивно.

Расчёт delta клиент делает на своей стороне (`(current - previous) / previous × 100`); бэкенд возвращает только две цифры.

#### Ошибки

| HTTP | `code`            | Причина                                          |
| ---- | ----------------- | ------------------------------------------------ |
| 400  | `BAD_REQUEST`     | `from`/`to`/`venueId` отсутствуют или невалидны |
| 401  | —                 | Нет токена / истёк                              |
| 403  | `FORBIDDEN`       | Не OWNER                                         |
| 404  | `VENUE_NOT_FOUND` | `venueId` не принадлежит владельцу              |

---

### 3. GET `/api/v1/reports/revenue-series`

Дневные (или для года — месячные) точки выручки — наполняют bar chart в overview.

#### Запрос

Query: общие параметры.

#### 200 OK

Для `period=MONTH`, range = (1 мая, 16 мая) — серия из 15 дней:

```json
[
  { "bucket": "2026-05-01T00:00:00Z", "revenue": 8400, "sessions": 22 },
  { "bucket": "2026-05-02T00:00:00Z", "revenue": 11200, "sessions": 28 },
  { "bucket": "2026-05-03T00:00:00Z", "revenue": 9100, "sessions": 24 },
  ...
]
```

Для `period=YEAR` — **месячные** bucket'ы:

```json
[
  { "bucket": "2026-01-01T00:00:00Z", "revenue": 245000, "sessions": 612 },
  { "bucket": "2026-02-01T00:00:00Z", "revenue": 268000, "sessions": 645 },
  { "bucket": "2026-03-01T00:00:00Z", "revenue": 281000, "sessions": 690 },
  { "bucket": "2026-04-01T00:00:00Z", "revenue": 247000, "sessions": 600 },
  { "bucket": "2026-05-01T00:00:00Z", "revenue": 142500, "sessions": 384 }
]
```

| Поле       | Тип     | Примечания                                                                |
| ---------- | ------- | ------------------------------------------------------------------------- |
| `bucket`   | ISO8601 | Начало bucket'а UTC. Для года — 1-е число месяца 00:00; иначе — 00:00 дня |
| `revenue`  | integer | Сумма по COMPLETED-сессиям внутри bucket'а                                |
| `sessions` | integer | Кол-во COMPLETED-сессий внутри bucket'а                                   |

> **Bucket coverage:** Бэкенд должен возвращать **каждый** bucket от `from` до `to` (пустые дни/месяцы — `revenue=0, sessions=0`). Чтобы временная ось графика на клиенте оставалась стабильной даже без данных.

---

### 4. GET `/api/v1/reports/tables`

Список **всех** столов выбранного заведения, отсортированный по выручке desc. Топ-N лимита нет — владельцу нужен полный список, чтобы увидеть, какой стол зарабатывает мало.

#### Запрос

Query: общие параметры.

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

| Поле           | Тип             | Примечания                                                                                          |
| -------------- | --------------- | --------------------------------------------------------------------------------------------------- |
| `tableId`      | uuid            | ID стола                                                                                            |
| `tableName`    | string \| null  | Может быть null/пусто (отобразится как `Table 2`)                                                   |
| `tableNumber`  | integer         | Уникальный порядковый номер в рамках заведения                                                      |
| `venueId`      | uuid            | venue фильтра (одинаково для всех строк)                                                            |
| `venueName`    | string          | Имя заведения (одинаково для всех строк)                                                            |
| `revenue`      | integer         | Сумма по COMPLETED-сессиям этого стола                                                              |
| `sessions`     | integer         | Кол-во COMPLETED-сессий этого стола                                                                 |
| `currency`     | enum            | Валюта                                                                                              |
| `deltaPercent` | integer \| null | Процентное изменение относительно **clipped previous**. При `compare=false` или нехватке данных — null |

Сортировка: `revenue DESC`. Tie-break: `tableNumber ASC`.

---

### 5. GET `/api/v1/reports/tables/{id}`

Детальная страница одного стола — KPI overview + тренд выручки по дням/месяцам + heatmap по часам/дням недели.

#### Path Params

- `id` (uuid) — ID стола.

#### Запрос

Query: общие параметры.

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
    "// ... всего 7 строк (Пн ... Вс)"
  ]
}
```

| Поле            | Тип            | Примечания                                                                                                                                                                                                                       |
| --------------- | -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `summary`       | TableReportRow | Та же схема, что и строка из `/api/v1/reports/tables`                                                                                                                                                                             |
| `revenueSeries` | RevenuePoint[] | То же правило bucket'ов, что у `/api/v1/reports/revenue-series` (по periodу — дневной/месячный), scope'нутое на этот стол                                                                                                         |
| `hourHeatmap`   | int[7][24]     | 7 строк (ISO 8601 Пн=index 0 … Вс=index 6) × 24 столбца (часы 0…23). Значение ячейки = сумма revenue для COMPLETED-сессий в этот день недели × час. Агрегируется по ВСЕМ датам в диапазоне `from`/`to` (период выбирает пользователь) |

Критичный формат heatmap:

- **Фиксированный размер:** всегда матрица `7 × 24`. Если данных нет — значение в ячейке 0.
- **Порядок дней недели:** ISO 8601 — Понедельник=0, Вторник=1, …, Воскресенье=6.
- **Порядок часов:** 0 = 00:00–01:00, 1 = 01:00–02:00, … 23 = 23:00–24:00 (в локальном времени; venue zone — при необходимости в v2).
- **Агрегация:** все сессии из диапазона группируются по часу `startedAt`.

#### Ошибки

| HTTP | `code`            | Причина                                            |
| ---- | ----------------- | -------------------------------------------------- |
| 404  | `TABLE_NOT_FOUND` | Стол с таким id отсутствует или принадлежит другому владельцу |
| 403  | `FORBIDDEN`       | Не OWNER                                           |

---

### 6. GET `/api/v1/reports/managers`

Список менеджеров, заработавших выручку в выбранном заведении (по убыванию выручки).

#### Запрос

Query: общие параметры.

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

| Поле          | Тип     | Примечания                                                          |
| ------------- | ------- | ------------------------------------------------------------------- |
| `managerId`   | string  | User ID — совпадает с `Session.managerId`                           |
| `name`        | string  | Отображаемое имя                                                    |
| `username`    | string  | Без `@`; в UI отображается как `@username`                          |
| `revenue`     | integer | Сумма по COMPLETED-сессиям, обработанным этим менеджером           |
| `sessions`    | integer | Кол-во COMPLETED-сессий этого менеджера                             |
| `cancelCount` | integer | Кол-во CANCELLED-сессий этого менеджера (нейтральные данные)        |
| `currency`    | enum    | Валюта                                                              |

Сортировка: `revenue DESC`. **Risk-скора / сигналов фрода нет** — UI показывает только "кто сколько выручки сделал и сколько сессий отменил".

> **Backend prerequisite:** В записи `Session` должен быть `managerId` (кто запустил/завершил). Определён в [session_api.md § Модели ответа](session_api.md#модели-ответа) и [home_page_api.md § Session](home_page_api.md#session).

---

### 7. GET `/api/v1/reports/managers/{id}`

Детальная страница менеджера — KPI overview + лог последних ~40 сессий.

#### Path Params

- `id` (uuid) — ID менеджера (user).

#### Запрос

Query: общие параметры.

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
      "cancelReason": "Нажал не на тот"
    }
  ]
}
```

| Поле         | Тип               | Примечания                                                  |
| ------------ | ----------------- | ----------------------------------------------------------- |
| `summary`    | ManagerReportRow  | Та же схема, что строка из `/api/v1/reports/managers`       |
| `sessionLog` | SessionLogEntry[] | Не более **40** строк, по убыванию `startedAt`              |

**Поля SessionLogEntry:**

| Поле              | Тип             | COMPLETED               | CANCELLED            |
| ----------------- | --------------- | ----------------------- | -------------------- |
| `sessionId`       | uuid            | ✅                      | ✅                   |
| `tableId`         | uuid            | ✅                      | ✅                   |
| `tableName`       | string \| null  | ✅ (может быть null)    | ✅ (может быть null) |
| `tableNumber`    | integer         | ✅                      | ✅                   |
| `venueName`       | string          | ✅                      | ✅                   |
| `customerName`    | string \| null  | ✅ (может быть null)    | ✅ (может быть null) |
| `startedAt`       | ISO8601         | ✅                      | ✅                   |
| `endedAt`         | ISO8601         | ✅                      | ✅                   |
| `status`          | enum            | `COMPLETED`             | `CANCELLED`          |
| `currency`        | enum            | ✅                      | ✅                   |
| `durationSeconds` | integer \| null | ✅ (секунды)            | null                 |
| `totalAmount`     | integer \| null | ✅ (целое, без копеек)  | null                 |
| `cancelReason`    | string \| null  | null                    | ✅ (1-200 симв.)     |

> **`discountPercent` в MVP отсутствует.** Связан с сигналами фрода, поэтому намеренно исключён. После боевых данных + калибровки threshold'ов может быть возвращён в v2.

#### Ошибки

| HTTP | `code`              | Причина                                          |
| ---- | ------------------- | ------------------------------------------------ |
| 404  | `MANAGER_NOT_FOUND` | Менеджер с таким id не существует или принадлежит другому владельцу |
| 403  | `FORBIDDEN`         | Не OWNER                                         |

---

### 8. GET `/api/v1/reports/forecast`

Прогноз на **конец текущего календарного периода** при сохранении текущего темпа. Показывается только в виде карточки над overview.

#### Запрос

Query: общие параметры.

> При `period=TODAY` клиент **не вызывает** этот эндпоинт (карточка в UI скрыта). Бэкенд для Today не обязан возвращать осмысленный ответ — допустимы `404` или `422 NOT_ENOUGH_DATA`.

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
    "// ... реальные дни наблюдений (`isProjection: false`) ...",
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

| Поле                  | Тип             | Примечания                                                                          |
| --------------------- | --------------- | ----------------------------------------------------------------------------------- |
| `points`              | ForecastPoint[] | Объединённая серия прошлое+проекция. `isProjection=false` — реальное, `true` — прогноз |
| `projectedTotal`      | integer         | **Ожидаемая суммарная выручка** до конца периода (`realSoFar + projection`)        |
| `previousPeriodTotal` | integer         | Полная выручка **full** previous calendar period (не clipped)                       |
| `currency`            | enum            | Валюта                                                                              |

**Поля ForecastPoint:**

| Поле           | Тип     | Примечания                                                          |
| -------------- | ------- | ------------------------------------------------------------------- |
| `bucket`       | ISO8601 | Начало bucket'а (то же правило, что у revenueSeries)                |
| `expected`     | integer | Ожидаемое значение (для прошлого — реальная выручка, для будущего — прогноз) |
| `lower`        | integer | Нижняя граница доверительного интервала                              |
| `upper`        | integer | Верхняя граница доверительного интервала                             |
| `isProjection` | boolean | `false` — прошлое реальное, `true` — спроектированное будущее       |

**Предлагаемый алгоритм (как в моке мобильного клиента):**

1. От начала периода до сегодня сложить реальный `revenueSeries`.
2. Рассчитать линейную регрессию (slope + intercept).
3. Спроектировать с сегодня до конца календарного периода (`projDays` дней).
4. `projectedTotal = realSoFar + Σ projection`.
5. `previousPeriodTotal = full previousCalendar.totalRevenue` (отличается от KPI delta; **не clipped**).
6. Доверительный интервал: ±15% (упрощённо; в v2 — regression standard error).

Бэкенд не обязан реализовывать алгоритм **дословно**; должны быть гарантированы только три контракта:

- `projectedTotal` имеет семантику "суммарно до конца периода".
- `previousPeriodTotal` — **full** previous calendar (для мая — полный апрель).
- Список `points` заполнен от начала периода **до конца периода** (прошлое + проекция).

#### Ошибки

| HTTP | `code`            | Причина                                                              |
| ---- | ----------------- | -------------------------------------------------------------------- |
| 422  | `NOT_ENOUGH_DATA` | Менее 7 дней истории (прогноз невозможен, карточка скрывается)      |
| 404  | `VENUE_NOT_FOUND` | venue не существует                                                  |
| 403  | `FORBIDDEN`       | Не OWNER                                                             |

---

## Сводка по эндпоинтам

| Method | Path                             | Cache  | Rate Limit |
| ------ | -------------------------------- | ------ | ---------- |
| GET    | `/api/v1/reports/venues`         | 60с    | 60/мин     |
| GET    | `/api/v1/reports/overview`       | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/revenue-series` | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/tables`         | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/tables/{id}`    | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/managers`       | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/managers/{id}`  | 5 мин  | 60/мин     |
| GET    | `/api/v1/reports/forecast`       | 15 мин | 30/мин     |

> Владелец не будет обновлять экран отчётов ежесекундно — 5-минутного server-side кэша на практике достаточно и нагрузка на БД существенно снижается. Cache key: `(ownerId, venueId, period, from, to, compare)`.

---

## Backend prerequisites

1. **`Session.managerId`** — поле "кто запустил/завершил". Определено в схемах Session в `session_api.md` и `home_page_api.md`. Используется отчётами по менеджерам.
2. **Server-side гарантия timestamp'ов сессий** — `startedAt`/`endedAt` не приходят от клиента (это уже обеспечивает `session_api.md`). Критично для корректной работы forecast и clipping.
3. **Учёт soft delete** — удалённые venue/table должны быть видны в исторических отчётах (audit). Клиент в пикере показывает только активные, но отчётные запросы смотрят и в историю.

---

## Открытые вопросы / решения

- [ ] `period=CUSTOM` сейчас в мобильном клиенте не используется. Может ли бэкенд игнорировать это значение и работать только по `from`/`to`? (Рекомендация: да, explicit `period` приходит всегда, но range остаётся источником истины.)
- [ ] Доверительный интервал forecast — достаточно ±15% или нужна regression standard error? (MVP: ±15%, в v2 улучшим.)
- [ ] Timezone heatmap'а — локальное время заведения или UTC? Сохранять ли UTC offset в настройках заведения? (Рекомендация: в MVP использовать timezone сервера владельца; в v2 — per-venue.)
- [ ] Multi-currency владелец (Бишкек + Стамбул) — что показывать в overview KPI при смеси валют? (В MVP scope одного заведения и так одна валюта; multi-venue агрегация — в v2.)
- [ ] Пустые списки venue / table / manager — бэкенд возвращает пустой массив `[]` или `204`? (Рекомендация: всегда `200 []` — клиент может отрендерить empty state.)
