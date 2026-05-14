# Subscription Review — İMPLEMENTASYON YOK

Doc kaynağı: [docs/subscription-api.md](../docs/subscription-api.md)  
Kod: **yok** (entity, controller, service, repository, DTO, scheduled job — hiçbiri yok)

---

## Durum

Doc'taki tüm subscription endpoint'leri:

| Method | Path | Doc | Implementasyon |
|--------|------|-----|----------------|
| `GET` | `/api/v1/subscription` | Subscription özeti + payment history | ❌ |
| `GET` | `/api/v1/subscription/pricing` | Fiyatlandırma config + tableCount | ❌ |
| `POST` | `/api/v1/subscription/checkout` | Payment kaydı oluştur (PENDING) | ❌ |
| `GET` | `/api/v1/subscription/payment/{id}` | Payment status polling | ❌ |
| `POST` | `/api/v1/subscription/payment/{id}/confirm` | Mock-mode payment confirmation | ❌ |

Bağımlı entity'ler (yok):

- `Subscription` (ownerId, status, source, startDate, endDate, gracePeriodEndsAt, …)
- `Payment` (subscriptionId, amount, currency, months, snapshots, status, providerPaymentId, …)

Eksik cron job:

- Daily 00:00 UTC: ACTIVE → GRACE → EXPIRED state transition

Eksik gate mekanizması:

- Tüm yazma endpoint'leri için "owner subscription expired/grace@0" kontrolü

**Bu sadece bir API gap'i değil — ürünün gelir modeli kayıp.** Sistem ücretsiz çalışıyor. Owner'ları ödemeye zorlayan teknik mekanizma yok.

---

## P0 — Subscription Gate Tüm Yazma Endpoint'lerinde Eksik

Doc [subscription-api.md §Subscription gate](../docs/subscription-api.md#subscription-gate--diğer-endpointlere-etkisi):

| Endpoint kategorisi | EXPIRED owner için |
|---------------------|---------------------|
| Session: start, pause, resume, finish | 403 `SUBSCRIPTION_REQUIRED` |
| Venue/table: create, update, delete | 403 `SUBSCRIPTION_REQUIRED` |
| Auth: invite-code | 403 `SUBSCRIPTION_REQUIRED` |

**Şu an hiçbir yerde uygulanmıyor.** EXPIRED owner sistemi sınırsız kullanabilir.

Önerilen pattern: bir `SubscriptionGuard` component'i + AOP aspect veya HandlerInterceptor:

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

Kullanım:

```java
@RequiresActiveSubscription
@Transactional
public SessionLiteResponse start(User user, StartSessionRequest req) { ... }
```

Veya tüm `/api/v1/**` POST/PUT/PATCH/DELETE'ler için bir HandlerInterceptor — daha az invasive.

---

## Implementation Roadmap

### Adım 1 — Entity'ler

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

### Adım 2 — Hesaplama mantığı

Doc'a göre snapshot rules:

- `Subscription.daysUntilExpiry = max(0, ceil((endDate - now) / 1d))`
- `Subscription.graceDaysRemaining = status == GRACE ? max(0, floor((gracePeriodEndsAt - now) / 1d)) : 0`
- Status: read-time recompute:
  - now < endDate → ACTIVE
  - now >= endDate ve now < gracePeriodEndsAt → GRACE
  - now >= gracePeriodEndsAt → EXPIRED

`SubscriptionService.getCurrent(owner)` her çağrıda recompute eder; cron sadece persist'i tutarlı tutar.

### Adım 3 — Config

`application.yml`'a:

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

`@ConfigurationProperties("subscription")` ile bind.

### Adım 4 — Endpoints

5 endpoint + `SubscriptionController` (`/api/v1/subscription`):

- `GET /` → `SubscriptionDetailResponse { subscription, payments }`
- `GET /pricing` → `SubscriptionPricingResponse`
- `POST /checkout` → `PaymentResponse` (Payment PENDING)
- `GET /payment/{id}` → `PaymentResponse`
- `POST /payment/{id}/confirm` → mock-only, production'da `404`/disabled

### Adım 5 — Register'da TRIAL oluştur

Doc [§ Yan etkiler](../docs/subscription-api.md#yan-etkiler--diğer-endpointlere-eklenmesi-gerekenler):
> 2. Register endpoint (`POST /auth/register`, role=OWNER) → başarılı kayıttan **hemen sonra** backend `Subscription { status: ACTIVE, source: TRIAL, startDate: now, endDate: now + freeTrialDays }` oluşturmalı.

[AuthServiceImpl.register](../src/main/java/kg/sportmanager/service/impl/AuthServiceImpl.java#L51-L83)'a OWNER için TRIAL subscription yaratma adımı ekle.

### Adım 6 — Daily cron job

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

`SpringBootApplication`'a `@EnableScheduling` ekle.

### Adım 7 — Subscription gate uygula

`@RequiresActiveSubscription` annotation veya merkezi HandlerInterceptor.

### Adım 8 — Profile entegrasyonu

Doc:
> Profile endpoint → response'a `subscription: { status, endDate, daysUntilExpiry, graceDaysRemaining }` eklenmeli.

`/profile` veya `/me` endpoint'i mevcut değil — auth response'larında User dönmüyor pure profile için. Ayrıca eklenmeli (subscription dışında da gerekli).

### Adım 9 — Webhook hazırlığı

Mock-mode Subscribe modülü çalışırken, real-mode Finik webhook URL'i için ayrı bir `POST /api/v1/webhooks/finik` controller'ı şimdiden iskelet olarak konulabilir. MVP'de skip.

---

## Hata Kodları (eklenmeli)

`GlobalExceptionHandler.MESSAGES` map'ine:

- `SUBSCRIPTION_REQUIRED`
- `NO_TABLES`
- `INVALID_DURATION`
- `PAYMENT_NOT_FOUND`
- `PAYMENT_ALREADY_PROCESSED`
- `PAYMENT_PROVIDER_ERROR`
- `PRICING_MISMATCH`

`AppException` ile fırlat, hep multilingual body.

---

## Risk: Schema değişiklikleri

`User` entity'sinde gerek var mı? Subscription `@ManyToOne User owner` ile bağlı, owner User tarafında ekstra alan gerekmez. Ama Profile endpoint'i için `subscription` info'sunu join etmek için query optimization gerekebilir.

`ddl-auto: update` ile entity'leri eklemek schema'yı extend eder. Mevcut OWNER kullanıcıları için TRIAL subscription'lar **manual backfill** gerekir (yeni register OWNER'lar için otomatik oluşur, eskiler bypass eder).

Production'da:

```sql
INSERT INTO subscriptions (id, owner_id, status, source, start_date, end_date, ...)
SELECT gen_random_uuid(), u.id, 'ACTIVE', 'TRIAL', NOW(), NOW() + INTERVAL '14 days', ...
FROM users u WHERE u.role = 'OWNER'
  AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.owner_id = u.id);
```

Flyway script olmadan bu manuel SQL çalıştırılması gerek.

---

## Effort Tahmini

| Görev | Süre |
|-------|------|
| Entity'ler + repository | 2 saat |
| Service iskeleti + computation logic | 4 saat |
| 5 endpoint + DTO'lar | 4 saat |
| Subscription gate (AOP veya interceptor) | 3 saat |
| Register TRIAL hook | 1 saat |
| Daily cron job | 2 saat |
| MESSAGES güncellemesi + test edilebilir hata akışı | 1 saat |
| Mock confirm endpoint + production disable | 1 saat |
| Test (unit + integration) | 4 saat |
| Mevcut OWNER'lar için backfill SQL | 1 saat |
| **Toplam** | **~3 gün** |

Finik gerçek entegrasyonu hariç. Finik dokümantasyonu geldiğinde +2-3 gün.

---

## Mobil İmpact

Mobil paket `packages/subscription` doc'a göre yazılmışsa:

- Subscription detay ekranı 404 alır.
- Checkout ekranı boş.
- Owner kullanım sınırı algılamıyor → ürün stratejisi sıfır.

**Önerilen ara çözüm:** MVP launch'ı geciktirilemiyorsa, backend her zaman "ACTIVE + TRIAL + endDate=now+10y" dönen stub'lar yayınla. UI çalışır, hiç kimse ödeme yapmaz, ama "Coming soon" yerine yeşil/ACTIVE göstergesi kalır. Production'a gerçek implementasyon eklenince stub'lar silinir.
