# Сессия — спецификация поля `customerName`

Опциональное поле, в котором при старте сессии сохраняется имя клиента.
Используется, чтобы быстро находить клиента по имени, а не по номеру стола,
и для аудита менеджеров в отчётах.

Верхнеуровневые правила также повторяются в [`session_api.md`](session_api.md)
и [`reports-api.md`](reports-api.md). Этот документ собирает **всё поведение
поля в одном месте**.

---

## 1. Цель

- **UX:** Менеджер в карточке стола видит «Стол 3 — Asan» и сопоставляет клиента
  с быстро меняющимся залом, когда на трёх столах одновременно играют три
  компании.
- **Аудит:** Владелец в [`/api/v1/reports/managers/{id}`](reports-api.md#7-get-apiv1reportsmanagersid)
  внутри `sessionLog[]` видит, каких клиентов обслуживал менеджер за период.

---

## 2. Жизненный цикл и неизменяемость

```
start (body.customerName)
   │
   │ normalize → trim, пусто → NULL
   │ validate → длина > 80 → 422 INVALID_CUSTOMER_NAME
   │
   ▼
Session.customerName  ←──── фиксированный snapshot
   │
   │ pause / resume / finish / cancel:
   │   в request body поля customerName НЕТ
   │   (в DTO оно не определено) → даже если клиент пришлёт, оно не парсится
   │
   ▼
response.customerName  (всегда одно и то же значение)
```

- **Пишется только в `start`.** Дальше никогда не меняется.
- В DTO `pause` / `resume` / `finish` / `cancel` поле `customerName`
  **намеренно не определено** — Jackson игнорирует unknown-поля; менеджер не
  может переписать имя для манипуляции отчётом. (См. [session_api.md § Критические правила](session_api.md#критические-правила) пункт 8.)

---

## 3. Нормализация и валидация

Сервер выполняет последовательно:

1. **Trim** — пробелы в начале/конце убираются.
2. **Проверка пусто** — если после trim длина 0, сохраняется `customerName = NULL`
   (ошибка не возвращается, поле опциональное).
3. **Проверка длины** — если после trim длина > 80:

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

> **Подсчёт символов:** Java `String.length()` (UTF-16 code unit). Эмодзи и
> длинные строки помещаются в лимит; для реального мобильного использования
> этого достаточно.

Колонка БД: `VARCHAR(80) NULL` — schema-уровневое ограничение совпадает с
нормализацией, защита в глубину.

---

## 4. Затронутые response-схемы

`customerName: string | null` возвращается в:

| Структура | Endpoint(ы) |
|---|---|
| `SessionLiteResponse` | `POST /api/v1/session/start`, `/pause`, `/resume` |
| `SessionResultResponse` | `POST /api/v1/session/{id}/finish`, `/cancel` |
| `SessionResponse` (home card) | блок `masa.session` в `GET /api/v1/venue/selected` |
| `SessionLogEntryResponse` | `sessionLog[]` в `GET /api/v1/reports/managers/{id}` |

В ответах `pause` / `resume` / `finish` / `cancel` поле тоже присутствует, но
значение — всегда то, что было записано при `start` (или `NULL`).

---

## 5. Поведение в Reports

- `sessionLog[]` отсортирован `startedAt DESC`; `customerName` возвращается как
  есть — без маскирования.
- Поиск по имени клиента делается на мобильной стороне (server-side фильтра
  `?customer=...` в MVP нет).
- Heatmap, revenue series, KPI менеджеров **не зависят** от `customerName`;
  поле появляется только в строках лога.

---

## 6. Обратная совместимость

- Backfill для существующих сессий **не выполняется**. В историческом
  7-месячном объёме данных `customer_name = NULL` — синтетические имена
  не пишем, чтобы не ломать смысл аудита.
- Старые мобильные клиенты не присылают `customerName` в `start` ⇒ поле
  сохраняется как `NULL`, поведение не меняется.
- Старые клиенты получат новое поле в ответах, но Jackson на их стороне
  спокойно его проигнорирует — breaking change нет.

---

## 7. Миграция

Flyway `V4__session_customer_name.sql`:

```sql
ALTER TABLE sessions
  ADD COLUMN customer_name VARCHAR(80);
```

Индекс не нужен — запросов по `customerName` нет.

---

## 8. Список error-кодов

| Code | HTTP | Триггер |
|---|:---:|---|
| `INVALID_CUSTOMER_NAME` | 422 | `customerName` после trim превышает 80 символов |

Остальные session-коды остаются без изменений — пустое или `null`-значение
само по себе не ошибка.
