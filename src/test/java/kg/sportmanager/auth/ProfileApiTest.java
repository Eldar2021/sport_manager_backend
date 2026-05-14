package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/profile";

    @Autowired
    private kg.sportmanager.repository.VenueRepository venueRepository;

    @Test
    @DisplayName("OWNER: profile döner — user + profileData{venuesCount, managersCount, subscription}")
    void owner_returnsFullProfile() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000070", "Test1234");
        Subscription sub = createActiveTrial(owner); // 14 gün aktif
        // 2 venue + 3 manager
        createVenueFor(owner, "Branch 1", 1);
        createVenueFor(owner, "Branch 2", 2);
        createManager("m1@x.com", "+996700000071", "P", owner);
        createManager("m2@x.com", "+996700000072", "P", owner);
        createManager("m3@x.com", "+996700000073", "P", owner);

        String access = accessFor(owner);

        MvcResult r = mockMvc.perform(withBearer(get(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.user.email").value("owner@x.com"))
                .andExpect(jsonPath("$.user.role").value("OWNER"))
                .andExpect(jsonPath("$.user.phone").value("+996700000070"))
                .andExpect(jsonPath("$.user.name").value("Test Owner"))
                .andExpect(jsonPath("$.profileData.venuesCount").value(2))
                .andExpect(jsonPath("$.profileData.managersCount").value(3))
                .andExpect(jsonPath("$.profileData.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.profileData.subscription.endDate").isNotEmpty())
                .andExpect(jsonPath("$.profileData.subscription.daysUntilExpiry").isNumber())
                .andExpect(jsonPath("$.profileData.subscription.graceDaysRemaining").value(0))
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.get("profileData").get("subscription").get("daysUntilExpiry").asInt())
                .isBetween(12, 14); // ~14g
    }

    @Test
    @DisplayName("MANAGER: profile döner — user dolu, profileData=null")
    void manager_returnsUserOnly_profileDataNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000074", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000075", "Test1234", owner);
        String access = accessFor(mgr);

        MvcResult r = mockMvc.perform(withBearer(get(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(mgr.getId().toString()))
                .andExpect(jsonPath("$.user.role").value("MANAGER"))
                .andExpect(jsonPath("$.user.email").value("mgr@x.com"))
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.has("profileData")).isTrue();
        assertThat(body.get("profileData").isNull()).isTrue();
    }

    @Test
    @DisplayName("OWNER + venue/manager yok → counts = 0, subscription doldu")
    void owner_emptyCounts() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000076", "Test1234");
        createActiveTrial(owner);
        String access = accessFor(owner);

        mockMvc.perform(withBearer(get(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.venuesCount").value(0))
                .andExpect(jsonPath("$.profileData.managersCount").value(0))
                .andExpect(jsonPath("$.profileData.subscription.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("OWNER + subscription yok → subscription:null")
    void owner_noSubscription_subscriptionNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000077", "Test1234");
        // intentionally no createActiveTrial
        String access = accessFor(owner);

        MvcResult r = mockMvc.perform(withBearer(get(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.venuesCount").value(0))
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.get("profileData").get("subscription").isNull()).isTrue();
    }

    @Test
    @DisplayName("OWNER + endDate geçmişte (status=ACTIVE) → recompute GRACE")
    void owner_subRecomputedToGrace() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000078", "Test1234");
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE) // stale; recompute should flip to GRACE
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .build());
        String access = accessFor(owner);

        mockMvc.perform(withBearer(get(URL), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.subscription.status").value("GRACE"))
                .andExpect(jsonPath("$.profileData.subscription.daysUntilExpiry").value(0))
                .andExpect(jsonPath("$.profileData.subscription.graceDaysRemaining").isNumber());
    }

    @Test
    @DisplayName("Auth yok → 400 UNAUTHORIZED (401 sadece expired için)")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Bozuk token → 400 INVALID_TOKEN")
    void invalidToken_returns400() throws Exception {
        MvcResult r = mockMvc.perform(withBearer(get(URL), "not.a.jwt"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_TOKEN");
    }

    @Test
    @DisplayName("Expired access token → 401 SESSION_EXPIRED (yalnız bu durum 401)")
    void expiredAccess_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000079", "Test1234");
        String expired = io.jsonwebtoken.Jwts.builder()
                .setId(java.util.UUID.randomUUID().toString())
                .setSubject(u.getId().toString())
                .claim("role", u.getRole().name())
                .claim("type", "access")
                .setIssuedAt(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                .setExpiration(new java.util.Date(System.currentTimeMillis() - 1_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-key-please-do-not-use-in-production-1234567890"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        MvcResult r = mockMvc.perform(withBearer(get(URL), expired))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    // ─── helper ─────────────────────────────────────────────────────────────────

    private Venue createVenueFor(User owner, String name, int number) {
        return venueRepository.saveAndFlush(Venue.builder()
                .owner(owner)
                .name(name)
                .number(number)
                .selected(number == 1)
                .build());
    }
}
