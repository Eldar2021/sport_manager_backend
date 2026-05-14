# Sport Manager Backend

REST API для управления спортивными объектами (бильярд / снукер / pool-холлы).
Java 21 / Spring Boot 4.0.6, PostgreSQL, JWT, Flyway, OpenAPI/Swagger.

> Полный код-ревью текущего состояния — см. [review/](review/) (есть русская версия в [review/ru/](review/ru/)).
> План работ — [review/PLAN.md](review/PLAN.md).

---

## Технологии

- Java 21, Spring Boot 4.0.6
- Spring Security + JWT (jjwt 0.11.5)
- Spring Data JPA, PostgreSQL
- Flyway (миграции схемы)
- Spring AOP (subscription gate)
- springdoc-openapi (Swagger UI)
- Lombok
- Bcrypt

---

## Быстрый старт (Docker Compose)

```bash
docker-compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

Postgres поднимется автоматически (port 5432), `JWT_SECRET` подставлен dev-значением.

---

## Запуск без Docker

Требования: Java 21, Maven (через `./mvnw`), PostgreSQL 14+ с базой `sport_manager`.

```bash
# 1. Поднять Postgres локально (без SSL)
createdb sport_manager

# 2. Запустить с dev-профилем
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Конфигурация (env vars)

| Переменная | Значение по умолчанию | Описание |
|-----------|----------------------|----------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/sport_manager` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | |
| `JWT_SECRET` | dev-default (не для прода!) | HMAC ключ для JWT |
| `JWT_ACCESS_EXPIRATION` | `900000` (15 мин) | TTL access-токена в мс |
| `JWT_REFRESH_EXPIRATION` | `2592000000` (30 дней) | TTL refresh-токена в мс |
| `JPA_DDL_AUTO` | `validate` (prod) / `update` (dev) | Hibernate DDL |
| `PORT` | `8080` | |
| `HIKARI_MAX_POOL` | `10` | Connection pool max |
| `PAYMENT_PROVIDER` | `MOCK` | `MOCK` или `FINIK` (FINIK пока не реализован) |

В production secret обязательно подменять — иначе любой может выпустить токены.

---

## Структура пакетов

```
kg.sportmanager/
├── configuration/   # SecurityConfiguration, MessageConfiguration, SwaggerConfig, SubscriptionConfig
├── controller/      # AuthController, HomePageController, SessionController, ReportsController,
│                    # ManagerController, SubscriptionController
├── dto/
│   ├── request/     # Login/Register/Refresh/Forgot, VenueRequest, TableRequest, Session*,
│   │                # CheckoutRequest, ConfirmPaymentRequest
│   └── response/    # AuthResponse, TokenPairResponse, UserResponse, ErrorResponse,
│                    # VenueResponse, TableResponse, SessionLite/ResultResponse,
│                    # ManagerResponse, Subscription*Response, PaymentResponse,
│                    # reports/* (Overview, RevenuePoint, TableReportRow, ManagerReport*, Forecast)
├── entity/          # User, InviteCode, Venue, Tables, Session, Subscription, Payment
├── exception/       # AppException, GlobalExceptionHandler (read MessageSource)
├── mapper/          # HomeMapper
├── repository/      # UserRepository, InviteCodeRepository, VenueRepository, TableRepository,
│                    # SessionRepository, ReportsRepository, SubscriptionRepository, PaymentRepository
├── security/        # JwtUtil, JwtAuthFilter, JwtAuthEntryPoint, JwtAccessDeniedHandler,
│                    # RequiresActiveSubscription, SubscriptionGate, RequestIdFilter
├── service/
│   └── impl/        # AuthServiceImpl, HomeServiceImpl, SessionServiceImpl, ReportsServiceImpl,
│                    # ManagerServiceImpl, SubscriptionServiceImpl, SubscriptionStatusJob
└── util/            # SessionMapper, CommonMapper
```

---

## API Endpoints

Все эндпоинты под префиксом `/api/v1/`.

### Auth (public + invite-code требует OWNER)
- `POST /api/v1/auth/login` — вход
- `POST /api/v1/auth/register` — регистрация (OWNER без invite, MANAGER с invite)
- `POST /api/v1/auth/refresh` — обновление токенов
- `POST /api/v1/auth/logout` — выход (требует Bearer)
- `POST /api/v1/auth/forgot-password` — **MVP: 503**, реализация в Phase 5+
- `POST /api/v1/auth/invite-code` — OWNER-only, генерация invite

### Home (Venue + Table)
- `GET /api/v1/venue/list`, `/selected`, `PATCH /selected` — OWNER+MANAGER
- `POST /create`, `PUT /{id}`, `DELETE /{id}` venue/table — OWNER + active subscription

### Session
- `POST /api/v1/session/start`, `/pause`, `/resume`, `/finish`, `/cancel` — OWNER+MANAGER + active subscription

### Reports (OWNER-only)
- `GET /api/v1/reports/venues`, `/overview`, `/revenue-series`, `/tables`, `/tables/{id}`,
  `/managers`, `/managers/{id}`, `/forecast`

### Managers (OWNER-only)
- `GET /api/v1/managers` — список менеджеров OWNER-а
- `DELETE /api/v1/managers/{id}` — soft-delete менеджера + active subscription

### Subscription (OWNER-only, MOCK режим)
- `GET /api/v1/subscription` — детали + история платежей
- `GET /api/v1/subscription/pricing` — цены + table count
- `POST /api/v1/subscription/checkout` — создать платёж (PENDING)
- `GET /api/v1/subscription/payment/{id}` — статус платежа
- `POST /api/v1/subscription/payment/{id}/confirm` — **mock-only**, симуляция PAID/FAILED

> Реальная интеграция с Finik произойдёт после подписания договора. Пока работает `provider=MOCK`.

### Health / Swagger (public)
- `GET /actuator/health` — для k8s/docker probes
- `GET /swagger-ui.html`

---

## Формат ошибок

Единый envelope для всех ошибок (включая auth):

```json
{
  "code": "VENUE_NOT_FOUND",
  "message": { "en": "...", "ru": "...", "ky": "..." },
  "details": null
}
```

Validation:

```json
{
  "code": "VALIDATION_ERROR",
  "message": { "en": "Validation failed", ... },
  "details": [
    { "field": "email", "rule": "email", "message": "must be a well-formed email address" }
  ]
}
```

Переводы — в `src/main/resources/messages*.properties`. Новый код ошибки → добавить во все три файла + бросить `AppException("CODE", HttpStatus.X)`.

---

## Тесты

**Тесты MVP-намеренно отсутствуют** для скорости запуска. План на Phase 5: Testcontainers + integration tests. См. [review/08-config-build-review.md](review/08-config-build-review.md) и [review/PLAN.md](review/PLAN.md).

---

## Subscription Gate

Все write-эндпоинты (session start/pause/resume/finish/cancel, venue/table create/update/delete, invite-code, manager delete) проверяют активность подписки владельца через AOP-аспект `SubscriptionGate`. EXPIRED или GRACE@0 → 403 `SUBSCRIPTION_REQUIRED`.

Новый OWNER при регистрации получает 14-дневный TRIAL автоматически.

---

## Migrations (Flyway)

```
src/main/resources/db/migration/
  V1__initial_schema.sql           # baseline (users, invite_codes, venues, tables, sessions)
  V2__user_owner_handle_lastseen_deleted.sql  # multi-tenant fields
  V3__subscriptions_payments.sql   # subscription + payment + TRIAL backfill
```

В проде: `ddl-auto=validate`, Hibernate только проверяет схему. Все DDL изменения — через новый `V*.sql`.

---

## Документация и обзор

- [docs/](docs/) — API контракты (turkish), для мобильной команды
- [review/](review/) — полный код-ревью (turkish)
- [review/ru/](review/ru/) — то же по-русски
- [review/PLAN.md](review/PLAN.md) — план работ по фазам
- [CLAUDE.md](CLAUDE.md) — заметки для AI-инструментов
