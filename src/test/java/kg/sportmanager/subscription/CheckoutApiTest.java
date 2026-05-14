package kg.sportmanager.subscription;

import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutApiTest extends SubscriptionTestSupport {

    private static final String URL = "/api/v1/subscription/checkout";

    private Map<String, Object> payload(Integer months) {
        Map<String, Object> m = new HashMap<>();
        if (months != null) m.put("months", months);
        return m;
    }

    @Test
    @DisplayName("Happy: 3 masa, 2 ay → 201 + PENDING payment, snapshot dolu, amount=200*3*2=1200")
    void happy_pendingPaymentWithSnapshot() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003200", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 3);

        MvcResult r = mockMvc.perform(postJsonWithBearer(URL, payload(2), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andExpect(jsonPath("$.paymentUrl").isEmpty())
                .andExpect(jsonPath("$.providerPaymentId").isEmpty())
                .andExpect(jsonPath("$.months").value(2))
                .andExpect(jsonPath("$.tableCountSnapshot").value(3))
                .andExpect(jsonPath("$.pricePerTableSnapshot").value(200))
                .andExpect(jsonPath("$.amount").value(1200))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.paidAt").isEmpty())
                .andExpect(jsonPath("$.failedAt").isEmpty())
                .andReturn();
        // Persist edildiğini doğrula
        String paymentId = body(r).get("id").asText();
        var saved = paymentRepository.findById(java.util.UUID.fromString(paymentId));
        assert saved.isPresent();
    }

    @Test
    @DisplayName("Masa yok → 422 NO_TABLES")
    void noTables_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003201", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(postJsonWithBearer(URL, payload(1), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(body(r), "NO_TABLES");
    }

    @Test
    @DisplayName("months < 1 → 422 VALIDATION_ERROR (bean validation @Min)")
    void monthsBelowMin_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003202", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(0), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("months > 12 → 422 VALIDATION_ERROR (@Max)")
    void monthsAboveMax_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003203", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(13), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("months null → 422")
    void monthsNull_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003204", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(null), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003205", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 1);
        User mgr = createManager("mgr@x.com", "+996700003206", "Test1234", owner);

        mockMvc.perform(postJsonWithBearer(URL, payload(1), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("EXPIRED owner checkout yapabilir (spec'e göre subscription gate'e takılmaz)")
    void expiredOwner_canCheckout() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003207", "Test1234");
        createExpired(owner);
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(1), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Owner'ın hiç subscription'ı yoksa → auto-create TRIAL ile checkout")
    void noSubscription_checkoutAutoCreatesTrial() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003208", "Test1234");
        // intentionally no createActiveTrial
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(1), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Subscription DB'de oluşturuldu
        assert subscriptionRepository.findByOwner(owner).isPresent();
    }

    @Test
    @DisplayName("Birden fazla checkout → birden fazla PENDING payment kabul edilebilir")
    void multipleCheckouts_allCreatePending() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003209", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 1);

        mockMvc.perform(postJsonWithBearer(URL, payload(1), accessFor(owner)))
                .andExpect(status().isCreated());
        mockMvc.perform(postJsonWithBearer(URL, payload(2), accessFor(owner)))
                .andExpect(status().isCreated());

        var sub = subscriptionRepository.findByOwner(owner).orElseThrow();
        assert paymentRepository.findBySubscriptionOrderByCreatedAtDesc(sub).size() == 2;
    }

    @Test
    @DisplayName("Auth yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(URL)
                        .contentType("application/json")
                        .content("{\"months\":1}"))
                .andExpect(status().isBadRequest());
    }
}
