# Subscription REST API — Контракт бэкенда

Эндпоинты подписки (членства), ожидаемые мобильным клиентом (`packages/subscription`) от бэкенда: тела запросов/ответов и обработка ошибок. Продуктовый поток см. [user-flow/subscription-flow.md](../user-flow/subscription-flow.md).

**Base URL:** `<BASE_URL>` (на мобильной стороне через `--dart-define=BASE_URL=...`)
**Content-Type:** `application/json; charset=utf-8`
**Authorization:** Все эндпоинты требуют `Authorization: Bearer <accessToken>`.

> Подписка существует **только на уровне OWNER**. Менеджеры пользуются подпиской владельца; на эндпоинты подписки им возвращается **403 FORBIDDEN**.

---

## Глобальные заголовки (для каждого запроса)

| Header            | Источник                              | Пример              | Обязательный |
| ----------------- | ------------------------------------- | ------------------- | ------------ |
| `Accept-Language` | Язык устройства (`en` / `ru` / `ky`)  | `ru`                | Опциональный |
| `versionBuild`    | Build number приложения               | `42`                | Опциональный |
| `os`              | Платформа                             | `ios` / `android`   | Опциональный |
| `Authorization`   | `Bearer <accessToken>`                | `Bearer eyJhbGc...` | Обязательный |

---

## Роли авторизации

| Endpoint                                                | OWNER | MANAGER |
| ------------------------------------------------------- | :---: | :-----: |
| GET `/api/v1/subscription`                              |  ✅   |   ❌    |
| GET `/api/v1/subscription/pricing`                      |  ✅   |   ❌    |
| POST `/api/v1/subscription/checkout`                    |  ✅   |   ❌    |
| GET `/api/v1/subscription/payment/{id}`                 |  ✅   |   ❌    |
| POST `/api/v1/subscription/payment/{id}/confirm` (mock) |  ✅   |   ❌    |

> На эти эндпоинты менеджер получает `403 FORBIDDEN`. Информация, которую менеджер должен видеть (например, blocked-screen, если у владельца EXPIRED), доходит **косвенно** через ответ `403 SUBSCRIPTION_REQUIRED` от других эндпоинтов (см. § Subscription gate ниже).

---

## Subscription gate — влияние на другие эндпоинты

Если подписка владельца `EXPIRED` либо `GRACE` с `graceDaysRemaining = 0`, **write-эндпоинты** (session start/pause/resume/finish, venue/table create/update/delete, manager invite) возвращают `403 SUBSCRIPTION_REQUIRED`. Read-эндпоинты (list / get) не затрагиваются.

```json
{
  "code": "SUBSCRIPTION_REQUIRED",
  "message": {
    "en": "Subscription expired. Renew to continue using core features.",
    "ru": "Подписка истекла. Продлите её, чтобы продолжить пользоваться основными функциями.",
    "ky": "Жазылуу бүттү. Негизги функцияларды колдонуу үчүн узартыңыз."
  },
  "details": null
}
```

| Категория эндпоинтов                       | Для EXPIRED владельца       |
| ------------------------------------------ | --------------------------- |
| Session: start, pause, resume, finish      | 403 `SUBSCRIPTION_REQUIRED` |
| Venue/table: create, update, delete        | 403 `SUBSCRIPTION_REQUIRED` |
| Auth: invite-code (manager invite)         | 403 `SUBSCRIPTION_REQUIRED` |
| Read-эндпоинты (list/get/profile)          | работают нормально          |
| Эндпоинты подписки (этот doc)              | работают нормально          |
| Logout, change-password, delete-account    | работают нормально          |

> При запросе от менеджера проверка подписки идёт **по подписке владельца** (того, к которому привязан менеджер). Это естественное поведение: у менеджера не может быть собственной подписки.

---

## Daily cron (переходы статусов)

Бэкенд раз в сутки (рекомендация: 00:00 UTC) сканирует все `ACTIVE / GRACE` подписки:

```
для каждой подписки S:
  if S.status == ACTIVE and S.endDate < now:
    S.status = GRACE
    S.gracePeriodEndsAt = S.endDate + config.gracePeriodDays   # по умолч. 5

  elif S.status == GRACE and S.gracePeriodEndsAt < now:
    S.status = EXPIRED
```

Мобильный клиент эти переходы не отслеживает — при каждом `GET /subscription` он читает актуальный state. Если даже cron ещё не отработал (например, запрос пришёл в 12:00 дня), эндпоинт должен **на лету пересчитать** правильный статус; cron только persist'ит.

---

## Стандартный формат ошибки

Тот же конверт, что в `auth-api.md` / `home_page_api.md`:

```json
{
  "code": "SUBSCRIPTION_REQUIRED",
  "message": {
    "en": "...",
    "ru": "...",
    "ky": "..."
  },
  "details": null
}
```

### Специфичные для подписки коды ошибок

| Code                        | HTTP | Значение                                                            |
| --------------------------- | :--: | ------------------------------------------------------------------- |
| `SUBSCRIPTION_REQUIRED`     | 403  | У владельца нет подписки / GRACE@0 / EXPIRED                       |
| `NO_TABLES`                 | 422  | У владельца нет столов, оплата не может быть рассчитана             |
| `INVALID_DURATION`          | 422  | `months` вне диапазона (`< minDurationMonths` или `>` max)         |
| `PAYMENT_NOT_FOUND`         | 404  | Запись с переданным `paymentId` отсутствует                         |
| `PAYMENT_ALREADY_PROCESSED` | 409  | Платёж `PAID`/`FAILED` нельзя подтвердить повторно                  |
| `PAYMENT_PROVIDER_ERROR`    | 502  | Ошибка на стороне 3rd-party (Finik)                                 |
| `PRICING_MISMATCH`          | 409  | Цена, отправленная клиентом, не совпадает с серверной               |
| `FORBIDDEN`                 | 403  | Эндпоинт вызвал менеджер                                            |
| `VALIDATION_ERROR`          | 422  | Валидация тела                                                      |

> `SUBSCRIPTION_REQUIRED` клиент ловит в глобальном handler'е → показывает пользователю диалог "Подписка истекла" + кнопку Continue.

---

## Domain-модели

### Subscription

Активная (или последняя) подписка владельца.

```ts
{
  id: string (uuid),
  ownerId: string (uuid),
  status: "ACTIVE" | "GRACE" | "EXPIRED",
  source: "TRIAL" | "PAID",
  startDate: string (ISO 8601),    // начало текущего периода
  endDate: string (ISO 8601),      // конец текущего периода (БЕЗ grace)
  gracePeriodEndsAt: string (ISO 8601) | null, // в GRACE/EXPIRED заполнено, в ACTIVE — null
  daysUntilExpiry: integer,        // floor((endDate - now) / 24h), clamp в 0; имеет смысл для ACTIVE/GRACE/EXPIRED
  graceDaysRemaining: integer,     // в GRACE [0..gracePeriodDays], в остальных — 0
  createdAt: string (ISO 8601),
  updatedAt: string (ISO 8601)
}
```

| Поле                 | Тип                            | Примечания                                                                                                                                                       |
| -------------------- | ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`                 | uuid                           | ID записи подписки (на каждый renewal новый id не создаётся — обновляется та же запись; альтернатива — новая запись + `previousId` для истории. В MVP — одна запись). |
| `ownerId`            | uuid                           | Владелец                                                                                                                                                         |
| `status`             | `ACTIVE` / `GRACE` / `EXPIRED` | UPPERCASE                                                                                                                                                        |
| `source`             | `TRIAL` / `PAID`               | Первая запись TRIAL, после первой оплаты — PAID (rename)                                                                                                         |
| `startDate`          | ISO 8601                       | Начало текущего периода (рекомендуется UTC)                                                                                                                       |
| `endDate`            | ISO 8601                       | Конец текущего периода (UTC). Grace **не входит**                                                                                                                |
| `gracePeriodEndsAt`  | ISO 8601 \| null               | `endDate + gracePeriodDays`. В ACTIVE рекомендуется null                                                                                                          |
| `daysUntilExpiry`    | int                            | Готовое число для UI-threshold'ов на клиенте                                                                                                                     |
| `graceDaysRemaining` | int                            | В GRACE [0, gracePeriodDays]; в ACTIVE/EXPIRED — `0`                                                                                                              |

> **Единственный источник истины для расчёта статуса — `endDate` и `gracePeriodEndsAt`.** Поля `daysUntilExpiry` / `graceDaysRemaining` — convenience-поля для клиента; бэкенд считает их на каждый ответ. Клиент доверяет этим числам и сам не пересчитывает (чтобы не было рассинхрона server time vs client time).

### SubscriptionPricing

Клиент запрашивает на экране checkout перед расчётом.

```ts
{
  pricePerTable: integer,          // месячная цена за один стол (config; целое, без копеек)
  currency: "KGS" | "USD" | "RUB" | "KZT" | "TRY",
  tableCount: integer,             // актуальное суммарное количество столов владельца
  monthlyAmount: integer,          // pricePerTable × tableCount (convenience)
  minDurationMonths: integer,      // обычно 1
  maxDurationMonths: integer,      // обычно 12
  gracePeriodDays: integer,        // обычно 5
  freeTrialDays: integer,          // для новых аккаунтов (для клиента — информативно)
  expiryWarningDays: integer       // обычно 3 — клиент ниже этого порога рисует "warning UI"
}
```

| Поле                | Примечания                                                              |
| ------------------- | ----------------------------------------------------------------------- |
| `pricePerTable`     | Из config бэкенда. Клиент **не кэширует**, при каждом checkout — fetch |
| `tableCount`        | Сумма столов всех заведений владельца, без soft-deleted                  |
| `monthlyAmount`     | `pricePerTable × tableCount`. При `tableCount = 0` → `0`                |
| `expiryWarningDays` | UI-threshold; бэкенд его не enforce'ит, только сообщает значение         |

> Если цена, переданная клиентом, отличается от ожидаемой бэкендом (например, пользователь держит открытым checkout, а бэкенд за это время обновил `pricePerTable`) — `POST /checkout` возвращает `409 PRICING_MISMATCH`, клиент рефетчит pricing.

### Payment

Запись об одном платеже. Из них собирается история подписки.

```ts
{
  id: string (uuid),
  subscriptionId: string (uuid),
  amount: integer,                 // итого оплачено (snapshot)
  currency: "KGS" | "USD" | "RUB" | "KZT" | "TRY",
  months: integer,                 // купленное число месяцев (>=1)
  tableCountSnapshot: integer,     // количество столов на момент оплаты
  pricePerTableSnapshot: integer,  // цена за стол на момент оплаты
  status: "PENDING" | "PAID" | "FAILED",
  paymentUrl: string | null,       // redirect 3rd-party; в mock-режиме null
  provider: "MOCK" | "FINIK",      // какой провайдер использовался
  providerPaymentId: string | null,// возврат Finik; в mock null
  createdAt: string (ISO 8601),
  paidAt: string (ISO 8601) | null,
  failedAt: string (ISO 8601) | null,
  failureReason: string | null
}
```

> **Правило snapshot:** при создании Payment копируются `pricePerTableSnapshot` и `tableCountSnapshot`. Если бэкенд потом изменит цену или владелец добавит/удалит стол — текущий Payment не затрагивается. (Та же философия, что у `tarifAmountSnapshot` сессии.)

### SubscriptionDetailResponse

Объединённая структура, возвращаемая эндпоинтом `GET /subscription`.

```ts
{
  subscription: Subscription,
  payments: Payment[]   // по убыванию даты; самые новые сверху
}
```

---

## Endpoints

### 1. Получить подписку

Вызывается при открытии экрана деталей подписки. Сводка текущей подписки + история платежей.

**Endpoint:**

```
GET /api/v1/subscription
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Ответ (200):**

```json
{
  "subscription": {
    "id": "aa0e8400-e29b-41d4-a716-446655440010",
    "ownerId": "user-001",
    "status": "ACTIVE",
    "source": "PAID",
    "startDate": "2026-04-15T10:30:00.000Z",
    "endDate": "2026-05-15T10:30:00.000Z",
    "gracePeriodEndsAt": null,
    "daysUntilExpiry": 15,
    "graceDaysRemaining": 0,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-04-15T10:35:00.000Z"
  },
  "payments": [
    {
      "id": "bb0e8400-e29b-41d4-a716-446655440020",
      "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
      "amount": 2000,
      "currency": "KGS",
      "months": 1,
      "tableCountSnapshot": 10,
      "pricePerTableSnapshot": 200,
      "status": "PAID",
      "paymentUrl": null,
      "provider": "MOCK",
      "providerPaymentId": null,
      "createdAt": "2026-04-15T10:30:00.000Z",
      "paidAt": "2026-04-15T10:30:30.000Z",
      "failedAt": null,
      "failureReason": null
    },
    {
      "id": "bb0e8400-e29b-41d4-a716-446655440019",
      "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
      "amount": 2000,
      "currency": "KGS",
      "months": 1,
      "tableCountSnapshot": 10,
      "pricePerTableSnapshot": 200,
      "status": "PAID",
      "paymentUrl": null,
      "provider": "MOCK",
      "providerPaymentId": null,
      "createdAt": "2026-03-15T10:30:00.000Z",
      "paidAt": "2026-03-15T10:30:14.000Z",
      "failedAt": null,
      "failureReason": null
    }
  ]
}
```

**Пример в TRIAL:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "ACTIVE",
    "source": "TRIAL",
    "startDate": "2026-04-30T08:00:00.000Z",
    "endDate": "2026-05-14T08:00:00.000Z",
    "gracePeriodEndsAt": null,
    "daysUntilExpiry": 14,
    "graceDaysRemaining": 0,
    "createdAt": "2026-04-30T08:00:00.000Z",
    "updatedAt": "2026-04-30T08:00:00.000Z"
  },
  "payments": []
}
```

**Пример в GRACE:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "GRACE",
    "source": "PAID",
    "startDate": "2026-03-15T10:30:00.000Z",
    "endDate": "2026-04-29T10:30:00.000Z",
    "gracePeriodEndsAt": "2026-05-04T10:30:00.000Z",
    "daysUntilExpiry": 0,
    "graceDaysRemaining": 4,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-04-30T00:00:00.000Z"
  },
  "payments": [ ... ]
}
```

**Пример в EXPIRED:**

```json
{
  "subscription": {
    "id": "aa0e8400-...",
    "ownerId": "user-001",
    "status": "EXPIRED",
    "source": "PAID",
    "startDate": "2026-03-15T10:30:00.000Z",
    "endDate": "2026-04-29T10:30:00.000Z",
    "gracePeriodEndsAt": "2026-05-04T10:30:00.000Z",
    "daysUntilExpiry": 0,
    "graceDaysRemaining": 0,
    "createdAt": "2026-01-15T10:30:00.000Z",
    "updatedAt": "2026-05-05T00:00:00.000Z"
  },
  "payments": [ ... ]
}
```

**Ошибки:**

| HTTP | `code`      | Причина                                                                                                                                                              |
| ---- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 401  | —           | Нет токена / истёк                                                                                                                                                  |
| 403  | `FORBIDDEN` | Пользователь — менеджер                                                                                                                                              |
| 404  | —           | У владельца нет ни одной записи подписки (только если TRIAL ещё не создан после register — неожиданная ситуация). Клиент может обработать как "TRIAL ещё не запустился" |

---

### 2. Получить pricing подписки

Вызывается при открытии checkout. Клиент **не кэширует**.

**Endpoint:**

```
GET /api/v1/subscription/pricing
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Ответ (200):**

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

**Случай `tableCount = 0`:**

```json
{
  "pricePerTable": 200,
  "currency": "KGS",
  "tableCount": 0,
  "monthlyAmount": 0,
  "minDurationMonths": 1,
  "maxDurationMonths": 12,
  "gracePeriodDays": 5,
  "freeTrialDays": 14,
  "expiryWarningDays": 3
}
```

> В этом случае клиент не открывает checkout, показывает empty state "Add a table to subscribe".

**Ошибки:**

| HTTP | `code`      | Причина                  |
| ---- | ----------- | ------------------------ |
| 401  | —           | Нет токена / истёк       |
| 403  | `FORBIDDEN` | Пользователь — менеджер  |

---

### 3. Создать checkout (старт платежа)

Вызывается при нажатии кнопки "Pay" на экране checkout. Бэкенд создаёт запись `Payment` (`status: PENDING`) и возвращает URL для редиректа на 3rd-party (real mode) или `null` (mock mode).

**Endpoint:**

```
POST /api/v1/subscription/checkout
```

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Тело:**

```json
{
  "months": 3
}
```

| Поле     | Тип     | Обязательное | Правила                                        |
| -------- | ------- | :----------: | ---------------------------------------------- |
| `months` | integer |      ✅      | `>= minDurationMonths`, `<= maxDurationMonths` |

> **В тело НЕ добавляются price или tableCount.** Бэкенд считает их сам (anti-tampering). Клиент сообщает только количество месяцев.

**Логика бэкенда:**

1. Получить актуальный `tableCount` владельца.
2. `tableCount == 0` → `422 NO_TABLES`.
3. Взять `pricePerTable` из config.
4. `amount = pricePerTable × tableCount × months`.
5. Создать `Payment { status: PENDING, snapshot... }`.
6. В real mode — запросить redirect URL у 3rd-party, в mock — вернуть `paymentUrl: null`.

**Ответ (201):**

```json
{
  "id": "bb0e8400-e29b-41d4-a716-446655440021",
  "subscriptionId": "aa0e8400-e29b-41d4-a716-446655440010",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "PENDING",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": null,
  "failureReason": null
}
```

**Пример real mode (Finik):**

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
  "paymentUrl": "https://pay.finik.kg/...?token=abc123",
  "provider": "FINIK",
  "providerPaymentId": "FINIK-9988",
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": null,
  "failureReason": null
}
```

**Ошибки:**

| HTTP | `code`                   | Причина                                                |
| ---- | ------------------------ | ------------------------------------------------------ |
| 422  | `VALIDATION_ERROR`       | `months` не integer                                    |
| 422  | `INVALID_DURATION`       | `months < min` или `months > max`                      |
| 422  | `NO_TABLES`              | У владельца нет столов                                 |
| 502  | `PAYMENT_PROVIDER_ERROR` | Не удалось создать пользователя в Finik / сетевая ошибка |
| 401  | —                        | Нет токена / истёк                                     |
| 403  | `FORBIDDEN`              | Пользователь — менеджер                                |

> `SUBSCRIPTION_REQUIRED` на этом эндпоинте **не возвращается** — EXPIRED-владелец как раз и пришёл, чтобы заплатить.

---

### 4. Получить статус платежа (polling)

Клиент после редиректа на 3rd-party опрашивает этот эндпоинт (интервал 5 сек, макс. 60 сек). Webhook на сервере уже всё проводит; клиент только читает "уже PAID?".

**Endpoint:**

```
GET /api/v1/subscription/payment/{id}
```

**Path Params:**

- `id` (uuid) — ID платежа

**Headers:** `Authorization: Bearer <accessToken>` (только OWNER)

**Ответ (200):**

```json
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
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": "2026-04-30T12:01:30.000Z",
  "failedAt": null,
  "failureReason": null
}
```

**Значения статуса:**

- `PENDING` — платёж ещё не завершён (клиент продолжает polling).
- `PAID` — платёж подтверждён, подписка **продлена**. Клиент показывает success-экран.
- `FAILED` — платёж отклонён. `failureReason` заполнен ("Insufficient funds", "Card declined" и т.п.).

**Ошибки:**

| HTTP | `code`              | Причина                                            |
| ---- | ------------------- | -------------------------------------------------- |
| 404  | `PAYMENT_NOT_FOUND` | Платёж с таким id отсутствует / принадлежит другому владельцу |
| 401  | —                   | Нет токена / истёк                                |
| 403  | `FORBIDDEN`         | Пользователь — менеджер                            |

---

### 5. Confirm Payment (только для mock)

**Только для MVP / mock mode.** В реальной интеграции с Finik эту роль выполняет webhook; этот эндпоинт будет удалён либо отключён (активен только когда `config.paymentProvider == "MOCK"`).

Вызывается клиентом, когда на экране mock-оплаты нажимается "Simulate success" / "Simulate failure".

**Endpoint:**

```
POST /api/v1/subscription/payment/{id}/confirm
```

**Path Params:**

- `id` (uuid) — ID платежа

**Тело:**

```json
{
  "outcome": "PAID"
}
```

| Поле      | Тип  | Обязательное | Правила             |
| --------- | ---- | :----------: | ------------------- |
| `outcome` | enum |      ✅      | `PAID` \| `FAILED`  |

**Логика бэкенда (`outcome = PAID`):**

1. Пометить Payment как `PAID`, `paidAt = now`.
2. Продлить подписку:
   - Если текущий `endDate > now` (early renew): `newEndDate = oldEndDate + months × 30 days`.
   - Иначе (GRACE / EXPIRED): `newEndDate = now + months × 30 days`.
3. `status = ACTIVE`, `source = PAID`, `gracePeriodEndsAt = null`, `startDate = newPeriodStart` (= now или oldEndDate).
4. Вернуть обновлённый `Payment`.

**Ответ (200) — outcome=PAID:**

```json
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
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": "2026-04-30T12:01:30.000Z",
  "failedAt": null,
  "failureReason": null
}
```

**Ответ (200) — outcome=FAILED:**

```json
{
  "id": "bb0e8400-...",
  "subscriptionId": "aa0e8400-...",
  "amount": 6000,
  "currency": "KGS",
  "months": 3,
  "tableCountSnapshot": 10,
  "pricePerTableSnapshot": 200,
  "status": "FAILED",
  "paymentUrl": null,
  "provider": "MOCK",
  "providerPaymentId": null,
  "createdAt": "2026-04-30T12:00:00.000Z",
  "paidAt": null,
  "failedAt": "2026-04-30T12:01:30.000Z",
  "failureReason": "Simulated failure"
}
```

**Ошибки:**

| HTTP | `code`                      | Причина                                            |
| ---- | --------------------------- | -------------------------------------------------- |
| 404  | `PAYMENT_NOT_FOUND`         | Платёж с таким id отсутствует / другого владельца |
| 409  | `PAYMENT_ALREADY_PROCESSED` | Платёж не в `PENDING` (уже PAID или FAILED)        |
| 422  | `VALIDATION_ERROR`          | `outcome` вне enum                                 |
| 403  | `FORBIDDEN`                 | Пользователь — менеджер                            |
| 404  | —                           | В real mode эндпоинт disabled (404 — логично)     |

> Этот эндпоинт **в production должен быть disabled**. В production-сборках клиент не вызывает mock-flow оплаты; Finik уходит в real flow и webhook триггерит бэкенд.

---

## Интеграция с профилем (резюме)

Эндпоинт профиля (на клиенте `AuthRepository.fetchProfile()`, на бэкенде `GET /profile` или `GET /me` — вне scope этой задачи, в отдельном doc'е) уже содержит поле `subscriptionEndDate` (см. `packages/auth/lib/models/profile_model.dart`). В rollout'е подписки в response профиля добавляется **сводка подписки**:

```json
{
  "user": { ... },
  "venuesCount": 2,
  "managersCount": 3,
  "subscription": {
    "status": "ACTIVE",
    "endDate": "2026-05-15T10:30:00.000Z",
    "daysUntilExpiry": 15,
    "graceDaysRemaining": 0
  }
}
```

> Старое поле `subscriptionEndDate` может быть **deprecated** и заменено новым объектом `subscription`. Клиент читает единый источник истины. Для обратной совместимости бэкенд какое-то время может заполнять оба поля.

Таким образом, при загрузке профиля без дополнительного round-trip виджет профиля рисует корректный subtitle ("Active · until X" / "Expires in N days" / "Grace · N days left" / "Expired").

---

## Сводка по эндпоинтам

| Method | Path                                        | Auth  | Rate Limit |
| ------ | ------------------------------------------- | ----- | ---------- |
| GET    | `/api/v1/subscription`                      | Owner | 30/минуту  |
| GET    | `/api/v1/subscription/pricing`              | Owner | 30/минуту  |
| POST   | `/api/v1/subscription/checkout`             | Owner | 5/минуту   |
| GET    | `/api/v1/subscription/payment/{id}`         | Owner | 60/минуту  |
| POST   | `/api/v1/subscription/payment/{id}/confirm` | Owner | 10/минуту  |

---

## Side effects — что нужно добавить в другие эндпоинты

Rollout подписки затрагивает следующие эндпоинты — клиент ожидает их поведения:

1. **Все write-эндпоинты** (session start/pause/resume/finish, venue/table create/update/delete, manager invite) → если подписка владельца `EXPIRED` или `GRACE@0`, должны возвращать `403 SUBSCRIPTION_REQUIRED` (см. § Subscription gate).
2. **Эндпоинт register** (`POST /auth/register`, role=OWNER) → **сразу после** успешной регистрации бэкенд должен создать `Subscription { status: ACTIVE, source: TRIAL, startDate: now, endDate: now + freeTrialDays }`.
3. **Эндпоинт профиля** → в response добавить `subscription: { status, endDate, daysUntilExpiry, graceDaysRemaining }`.

---

## Открытые вопросы / решения

- [ ] **История подписки** — обновлять одну и ту же запись (MVP) или на каждый renewal заводить новый `Subscription` (чище для аудита)? UI клиента показывает только текущую "current" и собирает историю из Payment'ов — в MVP решение **одна запись + update**, но бэкенд может выбрать и второй вариант (клиент это не заметит).
- [ ] **30-day month vs календарный месяц** — обсуждается в § 11 flow-документа. API-контракт это решение знать не обязан, но реализация бэкенда зависит.
- [ ] **Grace после TRIAL** — в flow-doc сказано, что после TRIAL тоже даём 5 дней grace? **Рекомендация:** да, чтобы правило было единым (в cron нет различия по `source`).
- [ ] **Формат webhook URL** — специфичен для Finik; когда придёт документация Finik, в этот doc будет добавлен `POST /webhooks/finik`.
- [ ] **Эндпоинт refund** — вне scope MVP; в v2 можно добавить `POST /subscription/payment/{id}/refund`.
- [ ] **Multi-region currency** — в MVP KGS фиксирован; бэкенд всё равно возвращает поле `currency` (future-proof).
- [ ] **UI настройки pricing** — `pricePerTable` меняется через админ-панель или через env-переменную? Деталь реализации бэкенда.
- [ ] **Pre-check эндпоинт** — нужно ли при открытии checkout проверять total через `POST /subscription/checkout/preview { months }`? **Рекомендация:** нет, расчёт клиента `pricing × months` + `409 PRICING_MISMATCH` — достаточная защита.
