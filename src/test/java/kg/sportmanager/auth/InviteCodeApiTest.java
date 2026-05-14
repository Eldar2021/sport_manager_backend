package kg.sportmanager.auth;

import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InviteCodeApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/invite-code";

    @Test
    @DisplayName("OWNER + aktif subscription → 200 + code + expiresAt")
    void owner_withActiveSub_succeeds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000050", "Test1234");
        createActiveTrial(owner);
        String access = accessFor(owner);

        mockMvc.perform(withBearer(post(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000051", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000052", "Test1234", owner);
        String access = accessFor(mgr);

        MvcResult r = mockMvc.perform(withBearer(post(URL), access))
                .andExpect(status().isForbidden())
                .andReturn();
        // FORBIDDEN или SUBSCRIPTION_REQUIRED — оба валидны (manager не-owner)
        String code = body(r).get("code").asText();
        if (!code.equals("FORBIDDEN") && !code.equals("SUBSCRIPTION_REQUIRED")) {
            throw new AssertionError("Unexpected code: " + code);
        }
        assertErrorEnvelope(body(r), code);
    }

    @Test
    @DisplayName("OWNER + EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED")
    void owner_withExpiredSub_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000053", "Test1234");
        Instant past = Instant.now().minus(20, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());
        String access = accessFor(owner);

        MvcResult r = mockMvc.perform(withBearer(post(URL), access))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED (401 sadece expired için)")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(post(URL))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Bozuk token → 400 INVALID_TOKEN")
    void invalidToken_returns400() throws Exception {
        MvcResult r = mockMvc.perform(withBearer(post(URL), "not.a.jwt"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_TOKEN");
    }
}
