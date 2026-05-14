package kg.sportmanager.subscription;

import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GetSubscriptionApiTest extends SubscriptionTestSupport {

    private static final String URL = "/api/v1/subscription";

    @Test
    @DisplayName("OWNER + aktif TRIAL → 200, subscription detail + payments boş")
    void activeTrial_returnsDetailWithEmptyPayments() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003000", "Test1234");
        Subscription sub = createActiveTrial(owner);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.id").value(sub.getId().toString()))
                .andExpect(jsonPath("$.subscription.ownerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.source").value("TRIAL"))
                .andExpect(jsonPath("$.subscription.startDate").isNotEmpty())
                .andExpect(jsonPath("$.subscription.endDate").isNotEmpty())
                .andExpect(jsonPath("$.subscription.gracePeriodEndsAt").isEmpty())
                .andExpect(jsonPath("$.subscription.daysUntilExpiry").isNumber())
                .andExpect(jsonPath("$.subscription.graceDaysRemaining").value(0))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    @Test
    @DisplayName("OWNER + PAID subscription + payment history → payments createdAt DESC")
    void paidWithPayments_returnsHistoryDesc() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003001", "Test1234");
        Subscription sub = createPaidActive(owner, 1);
        // İlki önce (en eski), sonra daha yeni
        Payment p1 = createPayment(sub, 1, 200L, Payment.Status.PAID);
        // Hibernate aynı transaction'da timestamp aynı olabilir; aralık bırakalım:
        Thread.sleep(10);
        Payment p2 = createPayment(sub, 3, 600L, Payment.Status.PAID);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.source").value("PAID"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].id").value(p2.getId().toString()))   // newest first
                .andExpect(jsonPath("$.payments[0].amount").value(600))
                .andExpect(jsonPath("$.payments[1].id").value(p1.getId().toString()))
                .andExpect(jsonPath("$.payments[1].amount").value(200));
    }

    @Test
    @DisplayName("Stale ACTIVE (endDate geçmişte) → recompute GRACE, gracePeriodEndsAt doldurulur")
    void staleActive_recomputedToGrace() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003002", "Test1234");
        createStaleActive(owner);

        MvcResult r = mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.status").value("GRACE"))
                .andExpect(jsonPath("$.subscription.gracePeriodEndsAt").isNotEmpty())
                .andExpect(jsonPath("$.subscription.daysUntilExpiry").value(0))
                .andReturn();
        int graceDays = body(r).get("subscription").get("graceDaysRemaining").asInt();
        assertThat(graceDays).isBetween(3, 5);                              // 5 gün grace - already 1 day passed
    }

    @Test
    @DisplayName("GRACE durumu — graceDaysRemaining doğru hesaplanır")
    void grace_graceDaysRemainingComputed() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003003", "Test1234");
        createGrace(owner, 3);

        MvcResult r = mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.status").value("GRACE"))
                .andReturn();
        int graceDays = body(r).get("subscription").get("graceDaysRemaining").asInt();
        assertThat(graceDays).isBetween(2, 3);
    }

    @Test
    @DisplayName("EXPIRED subscription → status=EXPIRED, graceDaysRemaining=0")
    void expiredSub_returnsExpiredStatus() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003004", "Test1234");
        createExpired(owner);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.status").value("EXPIRED"))
                .andExpect(jsonPath("$.subscription.daysUntilExpiry").value(0))
                .andExpect(jsonPath("$.subscription.graceDaysRemaining").value(0));
    }

    @Test
    @DisplayName("OWNER + subscription yok → 404, code 'REPORT_NOT_FOUND' OLMAMALI (spec: subscription özel kod)")
    void noSubscription_returns404WithProperCode() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003005", "Test1234");
        // intentionally no createActiveTrial

        MvcResult r = mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        String code = body(r).get("code").asText();
        // BUG: şu an REPORT_NOT_FOUND dönüyor — semantik olarak yanlış
        // Beklenen: SUBSCRIPTION_NOT_FOUND (subscription bağlamına özel)
        assertThat(code)
                .as("subscription endpoint REPORT_NOT_FOUND dönmemeli — bu Reports'a aitti")
                .isNotEqualTo("REPORT_NOT_FOUND")
                .isEqualTo("SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003006", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700003007", "Test1234", owner);

        MvcResult r = mockMvc.perform(getJsonWithBearer(URL, accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("EXPIRED subscription owner /subscription çekebilir (read endpoint, gate'siz)")
    void expiredOwner_canFetchOwnSubscription() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003008", "Test1234");
        createExpired(owner);

        // Gate read endpoint'lerini etkilemez — yenileme yapmak için subscription detayı görünmeli
        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk());
    }
}
