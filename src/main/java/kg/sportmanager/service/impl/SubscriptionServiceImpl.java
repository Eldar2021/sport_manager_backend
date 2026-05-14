package kg.sportmanager.service.impl;

import kg.sportmanager.configuration.SubscriptionConfig;
import kg.sportmanager.dto.request.CheckoutRequest;
import kg.sportmanager.dto.request.ConfirmPaymentRequest;
import kg.sportmanager.dto.response.PaymentResponse;
import kg.sportmanager.dto.response.SubscriptionDetailResponse;
import kg.sportmanager.dto.response.SubscriptionPricingResponse;
import kg.sportmanager.dto.response.SubscriptionResponse;
import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.repository.PaymentRepository;
import kg.sportmanager.repository.SubscriptionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
import kg.sportmanager.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final VenueRepository venueRepository;
    private final TableRepository tableRepository;
    private final SubscriptionConfig config;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /subscription
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SubscriptionDetailResponse getDetail(User user) {
        requireOwner(user);
        Subscription sub = subscriptionRepository.findByOwner(user)
                .orElseThrow(() -> new AppException("REPORT_NOT_FOUND", HttpStatus.NOT_FOUND));
        recomputeStatus(sub);
        subscriptionRepository.save(sub);

        List<PaymentResponse> payments = paymentRepository.findBySubscriptionOrderByCreatedAtDesc(sub)
                .stream().map(this::toPaymentResponse).toList();

        return SubscriptionDetailResponse.builder()
                .subscription(toSubscriptionResponse(sub))
                .payments(payments)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /subscription/pricing
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SubscriptionPricingResponse getPricing(User user) {
        requireOwner(user);
        int tableCount = countOwnerTables(user);
        long monthlyAmount = (long) config.getPricePerTable() * tableCount;
        return SubscriptionPricingResponse.builder()
                .pricePerTable(config.getPricePerTable())
                .currency(config.getCurrency())
                .tableCount(tableCount)
                .monthlyAmount(monthlyAmount)
                .minDurationMonths(config.getMinDurationMonths())
                .maxDurationMonths(config.getMaxDurationMonths())
                .gracePeriodDays(config.getGracePeriodDays())
                .freeTrialDays(config.getFreeTrialDays())
                .expiryWarningDays(config.getExpiryWarningDays())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /subscription/checkout
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse checkout(User user, CheckoutRequest request) {
        requireOwner(user);
        int months = request.getMonths();
        if (months < config.getMinDurationMonths() || months > config.getMaxDurationMonths()) {
            throw new AppException("INVALID_DURATION", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int tableCount = countOwnerTables(user);
        if (tableCount == 0) {
            throw new AppException("NO_TABLES", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Subscription sub = subscriptionRepository.findByOwner(user)
                .orElseGet(() -> createTrial(user)); // на случай отсутствия trial

        long amount = (long) config.getPricePerTable() * tableCount * months;
        Tables.Currency currency = parseCurrency(config.getCurrency());

        Payment.Provider provider = parseProvider(config.getPaymentProvider());

        Payment payment = Payment.builder()
                .subscription(sub)
                .amount(amount)
                .currency(currency)
                .months(months)
                .tableCountSnapshot(tableCount)
                .pricePerTableSnapshot(config.getPricePerTable())
                .status(Payment.Status.PENDING)
                .provider(provider)
                .build();

        // В MOCK режиме paymentUrl и providerPaymentId остаются null.
        // FINIK ещё не интегрирован — если кто-то поставит provider=FINIK, отдаём ошибку.
        if (provider == Payment.Provider.FINIK) {
            throw new AppException("PAYMENT_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY);
        }

        paymentRepository.save(payment);
        return toPaymentResponse(payment);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /subscription/payment/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(User user, String paymentId) {
        requireOwner(user);
        Payment payment = loadOwnedPayment(user, paymentId);
        return toPaymentResponse(payment);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /subscription/payment/{id}/confirm  (MOCK only)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse confirmMockPayment(User user, String paymentId, ConfirmPaymentRequest request) {
        requireOwner(user);

        if (!"MOCK".equalsIgnoreCase(config.getPaymentProvider())) {
            // В проде с реальным provider mock-confirm не должен работать
            throw new AppException("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }

        Payment payment = loadOwnedPayment(user, paymentId);
        if (payment.getStatus() != Payment.Status.PENDING) {
            throw new AppException("PAYMENT_ALREADY_PROCESSED", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        if ("PAID".equalsIgnoreCase(request.getOutcome())) {
            payment.setStatus(Payment.Status.PAID);
            payment.setPaidAt(now);
            extendSubscription(payment.getSubscription(), payment.getMonths(), now);
        } else {
            payment.setStatus(Payment.Status.FAILED);
            payment.setFailedAt(now);
            payment.setFailureReason("Simulated failure");
        }
        paymentRepository.save(payment);
        return toPaymentResponse(payment);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createTrial
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Subscription createTrial(User owner) {
        Instant now = Instant.now();
        Subscription sub = Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE)
                .source(Subscription.Source.TRIAL)
                .startDate(now)
                .endDate(now.plus(config.getFreeTrialDays(), ChronoUnit.DAYS))
                .build();
        return subscriptionRepository.save(sub);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gate helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Subscription getActiveOrThrow(User owner) {
        Subscription sub = subscriptionRepository.findByOwner(owner)
                .orElseThrow(() -> new AppException("SUBSCRIPTION_REQUIRED", HttpStatus.FORBIDDEN));
        recomputeStatus(sub);
        subscriptionRepository.save(sub);
        if (sub.getStatus() == Subscription.Status.EXPIRED) {
            throw new AppException("SUBSCRIPTION_REQUIRED", HttpStatus.FORBIDDEN);
        }
        if (sub.getStatus() == Subscription.Status.GRACE && graceDaysRemaining(sub) <= 0) {
            throw new AppException("SUBSCRIPTION_REQUIRED", HttpStatus.FORBIDDEN);
        }
        return sub;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(User owner) {
        return subscriptionRepository.findByOwner(owner)
                .map(s -> {
                    Subscription copy = s; // recompute без persist для read-only
                    Instant now = Instant.now();
                    if (copy.getStatus() == Subscription.Status.ACTIVE && copy.getEndDate().isBefore(now)) {
                        return false; // нужен recompute, но read-only — считаем неактивной
                    }
                    if (copy.getStatus() == Subscription.Status.EXPIRED) return false;
                    if (copy.getStatus() == Subscription.Status.GRACE) {
                        Instant graceEnd = copy.getGracePeriodEndsAt();
                        return graceEnd != null && graceEnd.isAfter(now);
                    }
                    return copy.getStatus() == Subscription.Status.ACTIVE;
                })
                .orElse(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void recomputeStatus(Subscription sub) {
        Instant now = Instant.now();
        if (sub.getStatus() == Subscription.Status.ACTIVE && sub.getEndDate().isBefore(now)) {
            sub.setStatus(Subscription.Status.GRACE);
            sub.setGracePeriodEndsAt(sub.getEndDate().plus(config.getGracePeriodDays(), ChronoUnit.DAYS));
        }
        if (sub.getStatus() == Subscription.Status.GRACE
                && sub.getGracePeriodEndsAt() != null
                && sub.getGracePeriodEndsAt().isBefore(now)) {
            sub.setStatus(Subscription.Status.EXPIRED);
        }
    }

    private void extendSubscription(Subscription sub, int months, Instant now) {
        // Early renew: продлеваем с конца, GRACE/EXPIRED: с сейчас
        if (sub.getEndDate() != null && sub.getEndDate().isAfter(now)) {
            sub.setEndDate(sub.getEndDate().plus(months * 30L, ChronoUnit.DAYS));
        } else {
            sub.setStartDate(now);
            sub.setEndDate(now.plus(months * 30L, ChronoUnit.DAYS));
        }
        sub.setStatus(Subscription.Status.ACTIVE);
        sub.setSource(Subscription.Source.PAID);
        sub.setGracePeriodEndsAt(null);
        subscriptionRepository.save(sub);
    }

    private int countOwnerTables(User owner) {
        return (int) tableRepository.countByOwner(owner);
    }

    private Payment loadOwnedPayment(User user, String paymentId) {
        UUID id;
        try {
            id = UUID.fromString(paymentId);
        } catch (Exception e) {
            throw new AppException("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new AppException("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!payment.getSubscription().getOwner().getId().equals(user.getId())) {
            throw new AppException("PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return payment;
    }

    private void requireOwner(User user) {
        if (user.getRole() != User.Role.OWNER) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private Tables.Currency parseCurrency(String c) {
        try {
            return Tables.Currency.valueOf(c.toUpperCase());
        } catch (Exception e) {
            return Tables.Currency.KGS;
        }
    }

    private Payment.Provider parseProvider(String p) {
        try {
            return Payment.Provider.valueOf(p.toUpperCase());
        } catch (Exception e) {
            return Payment.Provider.MOCK;
        }
    }

    private int graceDaysRemaining(Subscription sub) {
        if (sub.getStatus() != Subscription.Status.GRACE || sub.getGracePeriodEndsAt() == null) return 0;
        long days = ChronoUnit.DAYS.between(Instant.now(), sub.getGracePeriodEndsAt());
        return (int) Math.max(0, days);
    }

    private int daysUntilExpiry(Subscription sub) {
        if (sub.getEndDate() == null) return 0;
        long days = ChronoUnit.DAYS.between(Instant.now(), sub.getEndDate());
        return (int) Math.max(0, days);
    }

    private SubscriptionResponse toSubscriptionResponse(Subscription sub) {
        return SubscriptionResponse.builder()
                .id(sub.getId().toString())
                .ownerId(sub.getOwner().getId().toString())
                .status(sub.getStatus().name())
                .source(sub.getSource().name())
                .startDate(sub.getStartDate())
                .endDate(sub.getEndDate())
                .gracePeriodEndsAt(sub.getGracePeriodEndsAt())
                .daysUntilExpiry(daysUntilExpiry(sub))
                .graceDaysRemaining(graceDaysRemaining(sub))
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }

    private PaymentResponse toPaymentResponse(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId().toString())
                .subscriptionId(p.getSubscription().getId().toString())
                .amount(p.getAmount())
                .currency(p.getCurrency().name())
                .months(p.getMonths())
                .tableCountSnapshot(p.getTableCountSnapshot())
                .pricePerTableSnapshot(p.getPricePerTableSnapshot())
                .status(p.getStatus().name())
                .paymentUrl(p.getPaymentUrl())
                .provider(p.getProvider().name())
                .providerPaymentId(p.getProviderPaymentId())
                .createdAt(p.getCreatedAt())
                .paidAt(p.getPaidAt())
                .failedAt(p.getFailedAt())
                .failureReason(p.getFailureReason())
                .build();
    }
}
