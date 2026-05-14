package kg.sportmanager.subscription;

import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentStatusApiTest extends SubscriptionTestSupport {

    private static final String BASE = "/api/v1/subscription/payment/";

    @Test
    @DisplayName("Owner kendi PENDING payment → 200 + status PENDING")
    void ownerPendingPayment_returns200() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003300", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(getJsonWithBearer(BASE + p.getId(), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(p.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Owner kendi PAID payment → 200 + status PAID + paidAt")
    void ownerPaidPayment_returns200() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003301", "Test1234");
        Subscription sub = createPaidActive(owner, 1);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PAID);

        mockMvc.perform(getJsonWithBearer(BASE + p.getId(), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    @Test
    @DisplayName("Bilinmeyen payment ID → 404 PAYMENT_NOT_FOUND")
    void unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003302", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(getJsonWithBearer(BASE + UUID.randomUUID(), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "PAYMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("Bozuk UUID → 404 PAYMENT_NOT_FOUND")
    void invalidUuid_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003303", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(getJsonWithBearer(BASE + "not-a-uuid", accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Başka owner'ın payment'ı → 404 (multi-tenant, info-leak yok)")
    void otherOwnersPayment_returns404() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700003304", "Test1234");
        createActiveTrial(o1);
        User o2 = createOwner("o2@x.com", "+996700003305", "Test1234");
        Subscription sub2 = createActiveTrial(o2);
        Payment othersPayment = createPayment(sub2, 1, 200L, Payment.Status.PENDING);

        mockMvc.perform(getJsonWithBearer(BASE + othersPayment.getId(), accessFor(o1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003306", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Payment p = createPayment(sub, 1, 200L, Payment.Status.PENDING);
        User mgr = createManager("mgr@x.com", "+996700003307", "Test1234", owner);

        mockMvc.perform(getJsonWithBearer(BASE + p.getId(), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auth yok → 400")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(BASE + UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }
}
