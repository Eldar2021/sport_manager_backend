package kg.sportmanager.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfirmPaymentApiTest extends SubscriptionTestSupport {

    private String confirmUrl(UUID paymentId) {
        return "/api/v1/subscription/payment/" + paymentId + "/confirm";
    }

    @Test
    @DisplayName("outcome=PAID → 200 + payment PAID, subscription ACTIVE + source PAID + endDate uzatıldı")
    void outcomePaid_extendsSubscription() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003400", "Test1234");
        Subscription sub = createActiveTrial(owner);                // 14 gün TRIAL
        Instant beforeEnd = sub.getEndDate();
        Payment p = createPayment(sub, 3, 600L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()), Map.of("outcome", "PAID"), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        Subscription reloaded = subscriptionRepository.findByOwner(owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(reloaded.getSource()).isEqualTo(Subscription.Source.PAID);
        // Early renew (TRIAL hâlâ aktifken yenileme): endDate'ten itibaren +months*30 gün
        Instant expected = beforeEnd.plus(90, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(reloaded.getEndDate().getEpochSecond() - expected.getEpochSecond());
        assertThat(diffSeconds)
                .as("Early renew: yeni endDate = eski endDate + 3 ay (~90g)")
                .isLessThan(60);                                            // 60sn tolerans
    }

    @Test
    @DisplayName("outcome=FAILED → 200 + payment FAILED, subscription değişmez")
    void outcomeFailed_subscriptionUnchanged() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003401", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Subscription.Status statusBefore = sub.getStatus();
        Instant endBefore = sub.getEndDate();
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);

        MvcResult r = mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()),
                Map.of("outcome", "FAILED"), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedAt").isNotEmpty())
                .andExpect(jsonPath("$.failureReason").isNotEmpty())
                .andReturn();

        Subscription reloaded = subscriptionRepository.findByOwner(owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(statusBefore);
        assertThat(reloaded.getEndDate()).isEqualTo(endBefore);
    }

    @Test
    @DisplayName("EXPIRED owner için PAID confirm → subscription now'dan başlar, status ACTIVE")
    void expiredOwner_confirmPaid_startsFromNow() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003402", "Test1234");
        Subscription sub = createExpired(owner);
        Payment p = createPayment(sub, 2, 400L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()), Map.of("outcome", "PAID"), accessFor(owner)))
                .andExpect(status().isOk());

        Subscription reloaded = subscriptionRepository.findByOwner(owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(reloaded.getSource()).isEqualTo(Subscription.Source.PAID);
        assertThat(reloaded.getGracePeriodEndsAt()).isNull();
        // startDate ~now, endDate ~now+60g
        assertThat(reloaded.getStartDate()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(reloaded.getEndDate()).isAfter(Instant.now().plus(59, ChronoUnit.DAYS));
        assertThat(reloaded.getEndDate()).isBefore(Instant.now().plus(61, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("Zaten PAID payment confirm → 409 PAYMENT_ALREADY_PROCESSED")
    void alreadyPaid_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003403", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PAID);

        MvcResult r = mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()),
                Map.of("outcome", "PAID"), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "PAYMENT_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("Zaten FAILED payment confirm → 409 PAYMENT_ALREADY_PROCESSED")
    void alreadyFailed_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003404", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.FAILED);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()), Map.of("outcome", "FAILED"), accessFor(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("outcome bozuk değer → 422 VALIDATION_ERROR")
    void invalidOutcome_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003405", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()),
                Map.of("outcome", "UNKNOWN"), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("outcome eksik → 422")
    void missingOutcome_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003406", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Bilinmeyen payment id → 404")
    void unknownPayment_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003407", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(postJsonWithBearer(confirmUrl(UUID.randomUUID()),
                Map.of("outcome", "PAID"), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Başka owner'ın payment'ı → 404 (info-leak yok)")
    void otherOwnersPayment_returns404() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700003408", "Test1234");
        createActiveTrial(o1);
        User o2 = createOwner("o2@x.com", "+996700003409", "Test1234");
        Subscription sub2 = createActiveTrial(o2);
        Payment othersPayment = createPayment(sub2, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(othersPayment.getId()),
                Map.of("outcome", "PAID"), accessFor(o1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003410", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);
        User mgr = createManager("mgr@x.com", "+996700003411", "Test1234", owner);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()),
                Map.of("outcome", "PAID"), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GRACE owner için PAID confirm → ACTIVE'e geçer, gracePeriodEndsAt=null")
    void graceOwner_confirmPaid_clearsGrace() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003412", "Test1234");
        Subscription sub = createGrace(owner, 3);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(postJsonWithBearer(confirmUrl(p.getId()),
                Map.of("outcome", "PAID"), accessFor(owner)))
                .andExpect(status().isOk());

        Subscription reloaded = subscriptionRepository.findByOwner(owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(reloaded.getGracePeriodEndsAt()).isNull();
    }
}
