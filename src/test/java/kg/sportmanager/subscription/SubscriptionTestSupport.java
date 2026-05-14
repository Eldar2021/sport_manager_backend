package kg.sportmanager.subscription;

import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.managers.ManagersTestSupport;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Subscription API integration testleri için ortak helper'lar.
 * Owner / venue / table builder'larını ManagersTestSupport zinciri üzerinden miras alır;
 * Subscription state'i (TRIAL / PAID / GRACE / EXPIRED) ve Payment kaydı yaratmak için
 * özel metodlar ekler.
 */
public abstract class SubscriptionTestSupport extends ManagersTestSupport {

    // ─── Subscription builders (specific states) ───────────────────────────────

    protected Subscription createPaidActive(User owner, int monthsRemaining) {
        Instant now = Instant.now();
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE)
                .source(Subscription.Source.PAID)
                .startDate(now.minus(30, ChronoUnit.DAYS))
                .endDate(now.plus(monthsRemaining * 30L, ChronoUnit.DAYS))
                .build());
    }

    protected Subscription createGrace(User owner, int graceDaysRemaining) {
        Instant now = Instant.now();
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.GRACE)
                .source(Subscription.Source.PAID)
                .startDate(now.minus(30, ChronoUnit.DAYS))
                .endDate(now.minus(1, ChronoUnit.DAYS))
                .gracePeriodEndsAt(now.plus(graceDaysRemaining, ChronoUnit.DAYS))
                .build());
    }

    protected Subscription createExpired(User owner) {
        Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.PAID)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());
    }

    /** Stale state: status=ACTIVE ama endDate geçmişte — recompute GRACE'e taşımalı. */
    protected Subscription createStaleActive(User owner) {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .build());
    }

    protected Payment createPayment(Subscription sub, int months, long amount, Payment.Status status) {
        return paymentRepository.saveAndFlush(Payment.builder()
                .subscription(sub)
                .amount(amount)
                .currency(Tables.Currency.KGS)
                .months(months)
                .tableCountSnapshot(1)
                .pricePerTableSnapshot(200)
                .status(status)
                .provider(Payment.Provider.MOCK)
                .paidAt(status == Payment.Status.PAID ? Instant.now() : null)
                .build());
    }

    /** Bir owner için venue + N table oluşturur — pricing/checkout için. */
    protected Venue venueWithTables(User owner, int tableCount) {
        Venue v = createVenue(owner, "V", 1, true);
        for (int i = 1; i <= tableCount; i++) {
            createTable(v, "T" + i, i, 100, Tables.TarifType.HOUR);
        }
        return v;
    }

    // ─── HTTP helpers ───────────────────────────────────────────────────────────

    protected MockHttpServletRequestBuilder postJsonWithBearer(String url, Object body, String token) throws Exception {
        return post(url)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder getJsonWithBearer(String url, String token) {
        return get(url).header("Authorization", "Bearer " + token);
    }
}
