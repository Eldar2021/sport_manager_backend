# Ревью Subscription — РЕАЛИЗАЦИЯ ОТСУТСТВУЕТ

Источник: [docs/subscription-api.md](../../docs/subscription-api.md)  
Код: **нет** (нет ни сущностей, ни контроллеров, ни сервисов, ни репозиториев, ни DTO, ни scheduled job)

---

## Состояние

Все subscription-эндпоинты из docs:

| Method | Path                                        | Doc                                | Реализация |
| ------ | ------------------------------------------- | ---------------------------------- | ---------- |
| `GET`  | `/api/v1/subscription`                      | Сводка подписки + история платежей | ❌         |
| `GET`  | `/api/v1/subscription/pricing`              | Конфиг цен + tableCount            | ❌         |
| `POST` | `/api/v1/subscription/checkout`             | Создание Payment (PENDING)         | ❌         |
| `GET`  | `/api/v1/subscription/payment/{id}`         | Polling статуса платежа            | ❌         |
| `POST` | `/api/v1/subscription/payment/{id}/confirm` | Mock-режим подтверждения           | ❌         |

Необходимые сущности (нет):

- `Subscription` (ownerId, status, source, startDate, endDate, gracePeriodEndsAt, …)
- `Payment` (subscriptionId, amount, currency, months, snapshots, status, providerPaymentId, …)

Отсутствующий cron:

- Каждый день в 00:00 UTC: переходы ACTIVE → GRACE → EXPIRED

Отсутствующий механизм gate:

- Проверка «подписка владельца expired/grace@0» на всех write-эндпоинтах

**Это не просто пробел в API — потеряна revenue-модель.** Система работает бесплатно. Технического принуждения к оплате нет.

---

## P0 — Subscription gate отсутствует на всех write-эндпоинтах

Docs [subscription-api.md §Subscription gate](../../docs/subscription-api.md#subscription-gate--diğer-endpointlere-etkisi):

| Категория эндпоинта                   | Для EXPIRED владельца       |
| ------------------------------------- | --------------------------- |
| Session: start, pause, resume, finish | 403 `SUBSCRIPTION_REQUIRED` |
| Venue/table: create, update, delete   | 403 `SUBSCRIPTION_REQUIRED` |
| Auth: invite-code                     | 403 `SUBSCRIPTION_REQUIRED` |

**Сейчас не применяется нигде.** EXPIRED-владелец пользуется системой без ограничений.

Рекомендуемый паттерн: компонент `SubscriptionGuard` + AOP-аспект либо HandlerInterceptor:

```java
@Aspect @Component
@RequiredArgsConstructor
public class SubscriptionGuardAspect {
    private final SubscriptionService subscriptionService;

    @Before("@annotation(RequiresActiveSubscription)")
    public void check(JoinPoint jp) {
        User user = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User owner = user.getRole() == OWNER ? user : user.getOwner();
        if (!subscriptionService.isActive(owner)) {
            throw new AppException("SUBSCRIPTION_REQUIRED", FORBIDDEN);
        }
    }
}
```

Применение:

```java
@RequiresActiveSubscription
@Transactional
public SessionLiteResponse start(User user, StartSessionRequest req) { ... }
```

Либо HandlerInterceptor для всех POST/PUT/PATCH/DELETE под `/api/v1/**` — менее инвазивно.

---

## Дорожная карта реализации

### Шаг 1 — Сущности

```java
@Entity @Table(name = "subscriptions")
public class Subscription {
    @Id @GeneratedValue UUID id;
    @ManyToOne User owner;
    @Enumerated(STRING) Status status;          // ACTIVE, GRACE, EXPIRED
    @Enumerated(STRING) Source source;          // TRIAL, PAID
    Instant startDate;
    Instant endDate;
    Instant gracePeriodEndsAt;                   // nullable
    @CreationTimestamp Instant createdAt;
    @UpdateTimestamp Instant updatedAt;

    public enum Status { ACTIVE, GRACE, EXPIRED }
    public enum Source { TRIAL, PAID }
}

@Entity @Table(name = "payments")
public class Payment {
    @Id @GeneratedValue UUID id;
    @ManyToOne Subscription subscription;
    Long amount;
    @Enumerated(STRING) Tables.Currency currency;
    Integer months;
    Integer tableCountSnapshot;
    Integer pricePerTableSnapshot;
    @Enumerated(STRING) PaymentStatus status;    // PENDING, PAID, FAILED
    String paymentUrl;                            // nullable
    @Enumerated(STRING) Provider provider;        // MOCK, FINIK
    String providerPaymentId;
    @CreationTimestamp Instant createdAt;
    Instant paidAt;
    Instant failedAt;
    String failureReason;

    public enum PaymentStatus { PENDING, PAID, FAILED }
    public enum Provider { MOCK, FINIK }
}
```

### Шаг 2 — Логика вычислений

По docs snapshot-правила:

- `Subscription.daysUntilExpiry = max(0, ceil((endDate - now) / 1d))`
- `Subscription.graceDaysRemaining = status == GRACE ? max(0, floor((gracePeriodEndsAt - now) / 1d)) : 0`
- Статус (recompute при чтении):
  - now < endDate → ACTIVE
  - now >= endDate и now < gracePeriodEndsAt → GRACE
  - now >= gracePeriodEndsAt → EXPIRED

`SubscriptionService.getCurrent(owner)` пересчитывает на каждый вызов; cron только синхронизирует persistence.

### Шаг 3 — Конфиг

В `application.yml`:

```yaml
subscription:
  price-per-table: 200
  currency: KGS
  free-trial-days: 14
  grace-period-days: 5
  min-duration-months: 1
  max-duration-months: 12
  expiry-warning-days: 3
  payment-provider: MOCK
```

Биндить через `@ConfigurationProperties("subscription")`.

### Шаг 4 — Эндпоинты

5 эндпоинтов + `SubscriptionController` (`/api/v1/subscription`):

- `GET /` → `SubscriptionDetailResponse { subscription, payments }`
- `GET /pricing` → `SubscriptionPricingResponse`
- `POST /checkout` → `PaymentResponse` (Payment PENDING)
- `GET /payment/{id}` → `PaymentResponse`
- `POST /payment/{id}/confirm` → только в mock-режиме, в проде `404`/выключено

### Шаг 5 — TRIAL при register

Docs [§ Побочные эффекты](../../docs/subscription-api.md#yan-etkiler--diğer-endpointlere-eklenmesi-gerekenler):

> 2. Register endpoint (`POST /auth/register`, role=OWNER) → **сразу после** успешной регистрации backend создаёт `Subscription { status: ACTIVE, source: TRIAL, startDate: now, endDate: now + freeTrialDays }`.

В [AuthServiceImpl.register](../../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L51-L83) добавить шаг создания TRIAL для OWNER.

### Шаг 6 — Daily cron

```java
@Component @RequiredArgsConstructor
public class SubscriptionStatusJob {
    private final SubscriptionRepository repo;

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void transition() {
        Instant now = Instant.now();
        repo.findByStatus(Status.ACTIVE)
            .stream().filter(s -> s.getEndDate().isBefore(now))
            .forEach(s -> {
                s.setStatus(Status.GRACE);
                s.setGracePeriodEndsAt(s.getEndDate().plus(graceDays, DAYS));
                repo.save(s);
            });

        repo.findByStatus(Status.GRACE)
            .stream().filter(s -> s.getGracePeriodEndsAt().isBefore(now))
            .forEach(s -> s.setStatus(Status.EXPIRED));
    }
}
```

Не забыть `@EnableScheduling` на `SpringBootApplication`.

### Шаг 7 — Применить subscription gate

Аннотация `@RequiresActiveSubscription` или централизованный HandlerInterceptor.

### Шаг 8 — Интеграция с Profile

Docs:

> В response эндпоинта Profile добавляется `subscription: { status, endDate, daysUntilExpiry, graceDaysRemaining }`.

Эндпоинт `/profile` или `/me` отсутствует — auth-ответы не отдают чистый профиль. Нужно добавить (нужен и за пределами subscription).

### Шаг 9 — Подготовка webhook

Пока модуль работает в mock-режиме, можно сразу залить скелет контроллера `POST /api/v1/webhooks/finik` для будущей реальной интеграции. В MVP — пропустить.

---

## Коды ошибок (добавить)

В карту `GlobalExceptionHandler.MESSAGES`:

- `SUBSCRIPTION_REQUIRED`
- `NO_TABLES`
- `INVALID_DURATION`
- `PAYMENT_NOT_FOUND`
- `PAYMENT_ALREADY_PROCESSED`
- `PAYMENT_PROVIDER_ERROR`
- `PRICING_MISMATCH`

Бросать через `AppException` — всегда мультиязычное тело.

---

## Риск: изменения схемы

Нужны ли изменения в `User`? Subscription связана через `@ManyToOne User owner`, дополнительных полей в User не требуется. Но для эндпоинта Profile может понадобиться оптимизация join-а с subscription.

При `ddl-auto: update` сущности добавятся, схема расширится. Для существующих OWNER подписки TRIAL потребуется **руками backfill**-ить (новые регистрации создают сами, у старых не появится).

В проде:

```sql
INSERT INTO subscriptions (id, owner_id, status, source, start_date, end_date, ...)
SELECT gen_random_uuid(), u.id, 'ACTIVE', 'TRIAL', NOW(), NOW() + INTERVAL '14 days', ...
FROM users u WHERE u.role = 'OWNER'
  AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.owner_id = u.id);
```

Без Flyway придётся запускать этот SQL вручную.

---

## Оценка трудозатрат

| Задача                                  | Время      |
| --------------------------------------- | ---------- |
| Сущности + repository                   | 2 ч        |
| Каркас сервиса + computation logic      | 4 ч        |
| 5 эндпоинтов + DTO                      | 4 ч        |
| Subscription gate (AOP или interceptor) | 3 ч        |
| Хук TRIAL в register                    | 1 ч        |
| Daily cron                              | 2 ч        |
| Обновление MESSAGES + проверка ошибок   | 1 ч        |
| Mock confirm + отключение в проде       | 1 ч        |
| Тесты (unit + integration)              | 4 ч        |
| Backfill-SQL для существующих OWNER     | 1 ч        |
| **Итого**                               | **~3 дня** |

Без реальной интеграции с Finik. Когда будет документация Finik — +2-3 дня.

---

## Влияние на мобилу

Если мобильный пакет `packages/subscription` написан по docs:

- Экран подписки получит 404.
- Экран checkout пуст.
- Владелец не упирается в лимиты → монетизация = ноль.

**Временный компромисс:** если откладывать MVP-релиз нельзя, backend может отдавать stub-ответ `"ACTIVE + TRIAL + endDate=now+10y"`. UI работает, никто не платит, но вместо «Coming soon» висит зелёный/ACTIVE-индикатор. После реальной реализации stub'ы убираются.
