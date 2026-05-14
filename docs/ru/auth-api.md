# Auth REST API — Контракт бэкенда

Эндпоинты аутентификации, ожидаемые мобильным клиентом (`packages/auth`) от бэкенда: тела запросов/ответов, контракт заголовков и обработка ошибок.

**Base URL:** `<BASE_URL>` (на мобильной стороне передаётся через `--dart-define=BASE_URL=...`)
**Content-Type:** `application/json; charset=utf-8`

## Глобальные заголовки (для каждого запроса)

| Header            | Источник                                                          | Пример              | Обязательный |
| ----------------- | ----------------------------------------------------------------- | ------------------- | ------------ |
| `Accept-Language` | Язык устройства (`en` / `ru` / `ky`)                              | `ru`                | Опциональный |
| `versionBuild`    | Build number приложения                                           | `42`                | Опциональный |
| `os`              | Платформа                                                         | `ios` / `android`   | Опциональный |
| `Authorization`   | `Bearer <accessToken>` — только для аутентифицированных эндпоинтов | `Bearer eyJhbGc...` | По ситуации  |

> Эндпоинты login / register / forgot-password вызываются через **non-auth Dio instance** → у них нет заголовка `Authorization`.
> Refresh, logout, invite-code → через **bearer instance** → заголовок `Authorization` добавляется.

---

## 1. POST `/api/v1/auth/login`

**Auth:** нет

### Тело запроса

```json
{
  "username": "test",
  "password": "Test1234"
}
```

| Поле       | Тип    | Примечания                                          |
| ---------- | ------ | --------------------------------------------------- |
| `username` | string | Email, телефон или username — решение бэкенда       |
| `password` | string | Открытый текст (TLS)                                |

### 200 OK — тело ответа

```json
{
  "user": {
    "id": "user-001",
    "name": "Test Owner",
    "role": "OWNER", // должно быть "OWNER" или "MANAGER"
    "email": "test@tableflow.kg",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

### Ошибки

| HTTP | `code`                | Причина                       |
| ---- | --------------------- | ----------------------------- |
| 401  | `INVALID_CREDENTIALS` | Неверный username / password  |
| 423  | `ACCOUNT_LOCKED`      | Аккаунт заблокирован          |

> Клиент обрабатывает 401 на login особым образом: refresh не пытается, сразу делает logout / показывает ошибку.

---

## 2. POST `/api/v1/auth/register`

**Auth:** нет

Тело приходит в двух вариантах — поле `role` является дискриминатором.

### 2a) Регистрация владельца

```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "+996 700 000 003",
  "password": "Test1234",
  "role": "OWNER"
}
```

### 2b) Регистрация менеджера (invite-код обязателен)

```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+996 700 000 004",
  "password": "Test1234",
  "role": "MANAGER",
  "inviteCode": "INVITE-001"
}
```

| Поле         | Тип    | Обязательное          | Примечания                                |
| ------------ | ------ | --------------------- | ----------------------------------------- |
| `name`       | string | да                    |                                           |
| `email`      | string | да                    |                                           |
| `phone`      | string | да                    | E.164-подобный (`+996 ...`)               |
| `password`   | string | да                    |                                           |
| `role`       | enum   | да                    | `OWNER` \| `MANAGER`                      |
| `inviteCode` | string | да, при role=MANAGER  | Для OWNER поле вообще не передаётся       |

### 200 OK — тело ответа

То же, что у login: `{ user, accessToken, refreshToken }`.

```json
{
  "user": {
    "id": "user-001",
    "name": "Test Owner",
    "role": "OWNER", // должно быть "OWNER" или "MANAGER"
    "email": "test@tableflow.kg",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

### Ошибки

| HTTP | `code`                | Причина                                       |
| ---- | --------------------- | --------------------------------------------- |
| 400  | `INVALID_INVITE_CODE` | Для менеджера: код отсутствует/неверен/истёк |
| 409  | `EMAIL_ALREADY_USED`  | Email уже зарегистрирован                     |
| 409  | `PHONE_ALREADY_USED`  | Телефон уже зарегистрирован                   |
| 422  | validation            | Валидация на уровне поля                      |

---

## 3. POST `/api/v1/auth/refresh`

**Auth:** нет (access-token для самой операции refresh не обязателен — бэкенд доверяет только `refreshToken` из тела).

> Клиент вызывает этот эндпоинт через bearer Dio instance, поэтому заголовок `Authorization: Bearer <accessToken>` **может быть передан** (особенно если access-token ещё не истёк). Бэкенд **игнорирует** этот заголовок — решение о refresh принимается исключительно на основании поля `refreshToken` из тела запроса. Когда access-token истёк, вызывается тот же эндпоинт; наличие истёкшего токена в заголовке не должно мешать.

### Тело запроса

```json
{
  "refreshToken": "eyJhbGc..."
}
```

### 200 OK — тело ответа

**Возвращаются только токены; поля `user` нет.**

```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

> Клиент сохраняет оба токена заново — если поддерживается ротация refresh-токенов, бэкенд должен выдать новый refresh-токен (рекомендуется).

### Ошибки

| HTTP | Причина                                | Поведение клиента                                      |
| ---- | -------------------------------------- | ------------------------------------------------------ |
| 401  | Refresh-токен недействителен/истёк    | Локальное состояние очищается, переход на экран login |

> Если на refresh возвращается 401, клиент **не пробует refresh повторно** — просто выполняется logout.

---

## 4. POST `/api/v1/auth/logout`

**Auth:** требуется (`Authorization: Bearer <accessToken>`)

### Тело запроса

Пустое (`{}` или совсем без тела).

### 200 OK / 204 No Content

Тело не ожидается.

### Примечания

- Клиент **сначала** очищает локальные токены, затем отправляет запрос на сервер; ошибка от бэкенда игнорируется.
- Бэкенд всё равно должен инвалидировать refresh-токен (серверный blacklist).
- Если приходит 401 — клиент не реагирует, выход и так выполняется.

---

## 5. POST `/api/v1/auth/forgot-password`

**Auth:** нет

### Тело запроса

```json
{
  "email": "user@example.com"
}
```

### 200 OK

Тело ответа неважно. Из соображений конфиденциальности бэкенд должен возвращать 200 **независимо** от того, зарегистрирован email или нет (защита от перечисления). На указанный email отправляется новый пароль. Пользователь повторно входит с этим паролем. После этого ему предлагается сменить пароль.

### Ошибки

| HTTP | Причина                                                            |
| ---- | ------------------------------------------------------------------ |
| 422  | Формат email невалиден (предпочтительнее soft-fail)               |
| 404  | Email не найден                                                    |
| 400  | Аккаунт неактивен                                                  |

---

## 6. POST `/api/v1/auth/invite-code`

**Auth:** требуется, role = `OWNER`. Пользователь не-OWNER должен получить 403.

### Тело запроса

Пустое.

### 200 OK — тело ответа

```json
{
  "code": "INVITE-001",
  "expiresAt": "2026-05-04T12:34:56Z"
}
```

| Поле        | Тип              | Примечания                                       |
| ----------- | ---------------- | ------------------------------------------------ |
| `code`      | string           | Используется при регистрации менеджера          |
| `expiresAt` | ISO-8601 \| null | Если срока нет — `null`. Предпочтительно UTC    |

### Ошибки

| HTTP | `code`                  | Причина                                                                                          |
| ---- | ----------------------- | ------------------------------------------------------------------------------------------------ |
| 401  | —                       | Нет токена / истёк                                                                              |
| 403  | `FORBIDDEN`             | Пользователь не OWNER                                                                            |
| 403  | `SUBSCRIPTION_REQUIRED` | Подписка владельца `EXPIRED` или `GRACE@0` (write-gate)                                          |

> Правило глобального `SUBSCRIPTION_REQUIRED` gate'а см. [subscription-api.md § Subscription gate](subscription-api.md#subscription-gate--влияние-на-другие-эндпоинты).

---

## Models — Справочник полей

### `UserModel`

```json
{
  "id": "user-001",
  "name": "Test Owner",
  "role": "OWNER", // OWNER или MANAGER
  "email": "test@tableflow.kg",
  "phone": "+996 700 000 001"
}
```

| Поле    | Тип               | Обязательное | Примечания                                                |
| ------- | ----------------- | ------------ | --------------------------------------------------------- |
| `id`    | string            | да           | Генерируется сервером, стабильный                         |
| `name`  | string            | да           |                                                           |
| `role`  | `OWNER`/`MANAGER` | да           | UPPERCASE — значение JSON соответствует enum             |
| `email` | string \| null    | нет          |                                                           |
| `phone` | string \| null    | нет          |                                                           |

### `AuthTokensModel`

```json
{ "accessToken": "...", "refreshToken": "..." }
```

### `AuthResultModel` = `UserModel` + `AuthTokensModel` (плоский)

```json
{ "user": { ... }, "accessToken": "...", "refreshToken": "..." }
```

### `InviteCodeModel`

```json
{ "code": "INVITE-001", "expiresAt": "2026-05-04T12:34:56Z" }
```

---

## Формат ответа с ошибкой (рекомендация)

Бэкенд должен отдавать все ответы с ошибками в едином конверте — чтобы маппинг на стороне клиента в `AppException` / `AuthException` был простым:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": {
    "en": "Invalid username or password",
    "ru": "Неверный логин или пароль",
    "ky": "Логин же сырсөз туура эмес"
  },
  "details": null
}
```

| Поле      | Тип                              | Примечания                                                                                              |
| --------- | -------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `code`    | string (UPPER_SNAKE)             | На клиенте маппится в enum `AuthErrorCode`                                                              |
| `message` | object `{en, ru, ky}` \| string  | Предпочтительно три языка; если один — используется `Accept-Language`                                  |
| `details` | array \| null                    | При валидационных ошибках содержит `[{field, rule, message}]`, иначе `null` (см. home_page_api.md)     |

Карта `code` бэкенда → enum `AuthErrorCode` на клиенте:

| `code` бэкенда        | `AuthErrorCode` клиента | Типичный HTTP        |
| --------------------- | ----------------------- | -------------------- |
| `INVALID_CREDENTIALS` | `invalidCredentials`    | 401                  |
| `INVALID_INVITE_CODE` | `invalidInviteCode`     | 400                  |
| `SESSION_EXPIRED`     | `sessionExpired`        | 401 (путь refresh)   |
| `ACCOUNT_LOCKED`      | `accountLocked`         | 423                  |
| (всё остальное)       | `unknown`               | 4xx/5xx              |

> 401 = "сессия истекла → вернуться на login", 423 = "баннер о блокировке аккаунта". Оба обрабатываются глобально через `UnauthenticatedExceptionHandle`.

---

## Refresh-flow (резюме)

```
Клиент → любой bearer-эндпоинт → 401
        ↓
Клиент → POST /api/v1/auth/refresh { refreshToken }
   ├─ 200 → сохранить { accessToken, refreshToken }, повторить исходный запрос с новым токеном
   └─ 401 → очистить локальное состояние, перейти на login (refresh ПОВТОРНО не вызывается)
```

Критично для бэкенда:

- 401 возвращать только когда access-токен **действительно** expired/invalid — для отказа в доступе использовать **403**. Иначе клиент попадёт в цикл refresh.
- Если refresh-эндпоинт возвращает 401, клиент останавливается за один проход (рекурсивного refresh нет).
- Рекомендуется ротация refresh-токенов (новый refresh при каждом вызове); клиент уже сохраняет новый refresh-токен.

---
