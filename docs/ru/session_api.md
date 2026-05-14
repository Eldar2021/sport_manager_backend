# Session Management API

Эндпоинты для управления временем использования стола. Когда клиент садится за стол, стартует сессия; когда уходит — сессия завершается, и принимается оплата. В перерывах сессию можно поставить на паузу.

**Base URL:** `<BASE_URL>`
**Content-Type:** `application/json; charset=utf-8`
**Authorization:** Все эндпоинты требуют `Bearer <accessToken>`.

---

## Общая логика и правила

### Жизненный цикл сессии

```
                     ┌──────────────┐
                     │ Стол свободен│
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
                 │          │             │ (продолж.) │
                 └────┬─────┘             └────────────┘
                      │ finish
                      ▼
               ┌──────────────┐
               │ COMPLETED    │ ← сессия записана в БД
               │ оплата взята │   (попадает в отчёты)
               └──────────────┘

  на старте/active             на finish
  ───────────────              ─────────────
  cancel (60 сек)
  ───────────────
   CANCELLED
   (в отчёты не входит)
```

### Критичные правила

1. **Все timestamp пишет бэкенд.** Клиент НЕ передаёт `startedAt`, `pausedAt`, `resumedAt`, `endedAt`. Это защищает от того, чтобы менеджер мог фальсифицировать расчёт, изменив время на телефоне — **самое критичное правило, защищающее владельца от менеджера**.

2. **Правило snapshot.** При старте сессии значения `tarifAmount` и `tarifType` стола копируются. Если владелец меняет цену в середине сессии — текущая сессия не затрагивается.

3. **Используется sessionId, а не tableId.** Эндпоинты `pause`, `resume`, `finish`, `cancel` всегда работают с `sessionId`. У одного стола со временем может быть много сессий.

4. **`tableId` нужен только для `start`.** При старте новой сессии передаём tableId, потому что сессии ещё нет.

5. **Одновременно у стола может быть только 1 активная сессия.** Активная = `status = ACTIVE` или `status = PAUSED`. Попытка второго start для того же стола → `409 TABLE_HAS_ACTIVE_SESSION`.

6. **Завершённая сессия не открывается заново.** После ухода клиента стол снова свободен, для нового клиента стартует новая сессия. **Один и тот же стол может использоваться 20 раз в день.**

7. **История пауз хранится на бэкенде, на мобильный не возвращается.** Клиент использует только `totalPausedSeconds` для корректного отображения таймера. Подробные записи о паузах остаются в БД (для аудита/отчётов).

---

## Роли авторизации

| Endpoint                           | OWNER | MANAGER |
| ---------------------------------- | :---: | :-----: |
| POST `/api/v1/session/start`       |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/pause`  |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/resume` |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/finish` |  ✅   |   ✅    |
| POST `/api/v1/session/{id}/cancel` |  ✅   |  ✅\*   |

> \*Менеджер может вызвать `cancel`, но **только в течение первых 60 секунд** после старта сессии. После этого срока отменить может только владелец.

---

## Модели ответа

Используются две разные модели ответа. Какая возвращается — зависит от эндпоинта.

### SessionLite

Для активной (ACTIVE или PAUSED) сессии. Возвращается в ответах `start`, `pause`, `resume`, а также внутри карточки стола на главном экране.

```ts
{
  id: string (uuid),
  tableId: string (uuid),
  managerId: string (uuid),            // пользователь, запустивший сессию (owner или manager)
  status: "ACTIVE" | "PAUSED",
  startedAt: string (ISO 8601),
  totalPausedSeconds: integer,         // для расчёта таймера на клиенте
  pausedAt: string (ISO 8601) | null,  // в PAUSED — заполнено, в ACTIVE — null
  tarifAmountSnapshot: integer,
  tarifTypeSnapshot: "MINUTE" | "HOUR" | "DAY"
}
```

### SessionResult

Для завершённой сессии. Возвращается в ответах `finish` и `cancel`.

```ts
{
  id: string (uuid),
  tableId: string (uuid),
  managerId: string (uuid),            // пользователь, запустивший сессию (для аудита)
  status: "COMPLETED" | "CANCELLED",
  startedAt: string (ISO 8601),
  endedAt: string (ISO 8601),

  // COMPLETED — заполнено, CANCELLED — null:
  durationSeconds: integer | null,
  subtotal: integer | null,
  discountPercent: integer | null,
  totalAmount: integer | null,

  // CANCELLED — заполнено, COMPLETED — null:
  cancelReason: string | null
}
```

> **`managerId`:** ID пользователя, запустившего сессию (владелец — его ID; менеджер — ID менеджера). Используется модулем отчётов (производительность менеджеров / сигналы фрода). См. [reports-api.md](reports-api.md).

### Формулы расчёта

```
billableSeconds = (endedAt - startedAt) - totalPausedSeconds

# По tarifType:
if HOUR:    billableUnits = billableSeconds / 3600
if MINUTE:  billableUnits = billableSeconds / 60
if DAY:     billableUnits = billableSeconds / 86400

subtotal       = round(billableUnits * tarifAmountSnapshot)
discountAmount = round(subtotal * discountPercent / 100)
totalAmount    = subtotal - discountAmount
```

> **Округление:** стандартное математическое (0.5 → вверх). Без копеек, все суммы — целые числа.

---

## Стандартный формат ошибки

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

### Session-специфичные коды ошибок

| Code                        | HTTP | Значение                                                                                                                                          |
| --------------------------- | :--: | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SESSION_NOT_FOUND`         | 404  | Сессия не найдена                                                                                                                                  |
| `TABLE_NOT_FOUND`           | 404  | Стол не найден                                                                                                                                     |
| `TABLE_HAS_ACTIVE_SESSION`  | 409  | У стола уже есть активная/paused сессия                                                                                                            |
| `SESSION_NOT_ACTIVE`        | 409  | Сессия не в статусе ACTIVE (например, уже paused или completed)                                                                                    |
| `SESSION_NOT_PAUSED`        | 409  | Сессия не в статусе PAUSED (нельзя сделать resume)                                                                                                 |
| `SESSION_ALREADY_COMPLETED` | 409  | Сессия уже завершена, действие невозможно                                                                                                          |
| `CANCEL_WINDOW_EXPIRED`     | 422  | Окно отмены (60 секунд) истекло                                                                                                                    |
| `INVALID_DISCOUNT`          | 422  | Процент скидки не в диапазоне 0-100                                                                                                                |
| `SUBSCRIPTION_REQUIRED`     | 403  | Подписка владельца `EXPIRED` или `GRACE@0` (write-gate; см. [subscription-api.md](subscription-api.md#subscription-gate--влияние-на-другие-эндпоинты)) |

---

## Endpoints

### 1. Start Session

Когда менеджер нажимает кнопку start на пустом столе. Стартует новая сессия.

**Endpoint:**

```
POST /api/v1/session/start
```

**Тело:**

```json
{
  "tableId": "660e8400-e29b-41d4-a716-446655440001"
}
```

> `startedAt` в теле не передаётся. Бэкенд использует своё серверное время.

**Валидация:**
| Поле    | Тип   | Обязательное | Правила                          |
| ------- | ----- | :----------: | -------------------------------- |
| tableId | uuid  |      ✅      | Доступный пользователю стол      |

**Ответ (201) — SessionLite:**

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

**Ошибки:**

- `404 TABLE_NOT_FOUND`
- `409 TABLE_HAS_ACTIVE_SESSION`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

**Race condition:** Бэкенд блокирует стол в транзакции. Если приходят два параллельных start — один проходит, второй получает 409.

---

### 2. Pause Session

Когда клиент вышел покурить / в туалет — сессия ставится на паузу. Таймер останавливается.

**Endpoint:**

```
POST /api/v1/session/{id}/pause
```

**Path Params:**

- `id` (uuid) — ID сессии

**Тело:** пусто — `{}`

> `pausedAt` в теле не передаётся. Бэкенд пишет серверное время.

**Ответ (200) — SessionLite:**

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

**Примечания:**

- Одну сессию можно ставить на паузу **много раз**.
- Пока пауза активна, `totalPausedSeconds` ещё не обновляется — обновляется только при resume.

**Ошибки:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_NOT_ACTIVE`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 3. Resume Session

Возврат с паузы.

**Endpoint:**

```
POST /api/v1/session/{id}/resume
```

**Path Params:**

- `id` (uuid) — ID сессии

**Тело:** пусто — `{}`

**Ответ (200) — SessionLite:**

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

**Примечания:**

- `totalPausedSeconds` уже обновлён (10 минут = 600 секунд).
- При новой паузе `totalPausedSeconds` суммируется.
- `pausedAt` снова null (из PAUSED перешли в ACTIVE).

**Ошибки:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_NOT_PAUSED`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 4. Finish Session

Клиент уходит, принимается оплата, сессия завершается. **Расчёт и сохранение происходят в один шаг.**

**Endpoint:**

```
POST /api/v1/session/{id}/finish
```

**Path Params:**

- `id` (uuid) — ID сессии

**Тело:**

```json
{
  "discountPercent": 10
}
```

**Валидация:**
| Поле            | Тип     | Обязательное | Правила              |
| --------------- | ------- | :----------: | -------------------- |
| discountPercent | integer |      ❌      | 0-100, по умолч. 0   |

> `endedAt` в теле не передаётся. Бэкенд пишет серверное время.

**Логика бэкенда:**

1. Если сессия в PAUSED — автоматически resume, затем finish.
2. `endedAt` = now (server time)
3. `durationSeconds` = (endedAt - startedAt) - totalPausedSeconds
4. `subtotal` = round(durationSeconds / unitDivisor × tarifAmountSnapshot)
5. `totalAmount` = subtotal - round(subtotal × discountPercent / 100)
6. Status = COMPLETED, стол освобождается.

**Ответ (200) — SessionResult:**

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

**Пример расчёта:**

- Общая длительность: 18:42 → 20:12 = 90 минут = 5400 секунд
- Пауза: 600 секунд
- billableSeconds: 5400 - 600 = 4800 секунд = 80 минут = 1.333 часа
- subtotal: round(1.333 × 250) = 333 KGS
- скидка: round(333 × 10 / 100) = 33 KGS
- totalAmount: 333 - 33 = 300 KGS

**Примечания:**

- После finish стол можно сразу использовать для нового клиента.

**Ошибки:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_ALREADY_COMPLETED`
- `422 INVALID_DISCOUNT`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

### 5. Cancel Session

Ситуация "случайно нажал start". Сессия отменяется, в отчёты не попадает.

**Endpoint:**

```
POST /api/v1/session/{id}/cancel
```

**Path Params:**

- `id` (uuid) — ID сессии

**Тело:**

```json
{
  "reason": "Нажал не на тот стол"
}
```

**Валидация:**
| Поле   | Тип    | Обязательное | Правила          |
| ------ | ------ | :----------: | ---------------- |
| reason | string |      ✅      | 1-200 символов   |

> Менеджер может отменить **только в первые 60 секунд**. После 60 секунд → `422 CANCEL_WINDOW_EXPIRED`. Запросы на отмену вне окна доступны только владельцу.

**Ответ (200) — SessionResult:**

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
  "cancelReason": "Нажал не на тот стол"
}
```

**Примечания:**

- При cancel стол освобождается.
- Отменённые сессии **видны в отчётах** (для аудита), но **не учитываются** в расчёте выручки.
- В месячном отчёте владелец должен видеть "Менеджер X в этом месяце отменил 12 сессий" — чтобы выявлять подозрительную активность.

**Ошибки:**

- `404 SESSION_NOT_FOUND`
- `409 SESSION_ALREADY_COMPLETED`
- `422 CANCEL_WINDOW_EXPIRED`
- `403 FORBIDDEN`
- `403 SUBSCRIPTION_REQUIRED` — подписка владельца `EXPIRED` / `GRACE@0`

---

## Сводка по эндпоинтам

| Method | Path                                 | Тип ответа    | Auth   | Окно отмены        |
| ------ | ------------------------------------ | ------------- | ------ | ------------------ |
| POST   | `/api/v1/session/start`              | SessionLite   | Both   | —                  |
| POST   | `/api/v1/session/{id}/pause`         | SessionLite   | Both   | —                  |
| POST   | `/api/v1/session/{id}/resume`        | SessionLite   | Both   | —                  |
| POST   | `/api/v1/session/{id}/finish`        | SessionResult | Both   | —                  |
| POST   | `/api/v1/session/{id}/cancel`        | SessionResult | Both\* | 60 сек (manager)   |

\*Для менеджера cancel доступен только в первые 60 секунд; далее — только владелец.

---

## Заметки для мобильного клиента

### Расчёт таймера сессии (client-side)

При отображении живого таймера на телефоне менеджера:

```
# В ACTIVE:
elapsedSeconds = (now - startedAt) - totalPausedSeconds

# В PAUSED:
elapsedSeconds = (pausedAt - startedAt) - totalPausedSeconds
```

- Сессия в ACTIVE: таймер постоянно растёт.
- Сессия в PAUSED: таймер останавливается, остаётся фиксированным на `pausedAt`.
- Разница между серверным и клиентским временем (`server_time_offset`) должна вычисляться при login и применяться в таймере.

### Правило UI для Pause/Resume

- Сессия ACTIVE: видны кнопки `[Пауза]` и `[Завершить]`.
- Сессия PAUSED: видны кнопки `[Продолжить]` и `[Завершить]`. Таймер становится серым.

### Видимость кнопки Cancel

- Первые 60 секунд: для менеджера видна кнопка `[Я ошибся]`.
- После 60 секунд кнопка у менеджера исчезает.
- Владельцу видна всегда.

---

## Открытые вопросы / решения

- [ ] Хватит ли менеджеру 60 секунд на cancel? Нужен реальный тест.
- [ ] Длительность паузы может быть неограниченной? (например, клиент ушёл "покурить" на 3 часа) Нужен ли автоматический timeout/auto-resume?
- [ ] Действительно ли в MVP нужен `tarifType: DAY`?
- [ ] Нужен ли отдельный эндпоинт `GET /sessions/active` для списка активных сессий?
