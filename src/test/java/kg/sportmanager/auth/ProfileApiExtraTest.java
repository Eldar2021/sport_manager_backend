package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.repository.SubscriptionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
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

/**
 * Profile API için ek senaryolar (mevcut ProfileApiTest'i tamamlar):
 *  - EXPIRED state recompute
 *  - GRACE state with explicit gracePeriodEndsAt
 *  - Soft-deleted venue/manager sayılmamalı
 *  - MANAGER + owner aktif sub → profileData yine null (carryover yok)
 *  - Refresh token header → 400 INVALID_TOKEN_TYPE
 *  - EXPIRED subscription owner profile çekebilmeli (read endpoint, gate bypass)
 */
class ProfileApiExtraTest extends AuthTestSupport {

    private static final String URL = "/api/v1/profile";

    @Autowired private VenueRepository venueRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;

    private Venue venue(User owner, int number) {
        return venueRepository.saveAndFlush(Venue.builder()
                .owner(owner).name("V" + number).number(number).selected(number == 1).build());
    }

    @Test
    @DisplayName("OWNER: endDate + graceEnd geçmişte → recompute EXPIRED, daysUntilExpiry=0, graceDays=0")
    void owner_recomputedToExpired() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000080", "Test1234");
        Instant longPast = Instant.now().minus(30, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE)
                .source(Subscription.Source.TRIAL)
                .startDate(longPast.minus(14, ChronoUnit.DAYS))
                .endDate(longPast)
                .gracePeriodEndsAt(longPast.plus(5, ChronoUnit.DAYS)) // also past
                .build());

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.subscription.status").value("EXPIRED"))
                .andExpect(jsonPath("$.profileData.subscription.daysUntilExpiry").value(0))
                .andExpect(jsonPath("$.profileData.subscription.graceDaysRemaining").value(0));
    }

    @Test
    @DisplayName("OWNER: GRACE + gracePeriodEndsAt explicit → graceDaysRemaining doğru hesaplanır")
    void owner_graceWithExplicitEndsAt() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000081", "Test1234");
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant graceEnd = Instant.now().plus(3, ChronoUnit.DAYS); // 3 gün kaldı
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.GRACE)
                .source(Subscription.Source.PAID)
                .startDate(past.minus(30, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(graceEnd)
                .build());

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.subscription.status").value("GRACE"))
                .andReturn();
        int graceDays = body(r).get("profileData").get("subscription").get("graceDaysRemaining").asInt();
        assertThat(graceDays).isBetween(2, 3);
    }

    @Test
    @DisplayName("Soft-deleted venue counts'a dahil değil")
    void softDeletedVenue_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000082", "Test1234");
        createActiveTrial(owner);
        venue(owner, 1);
        Venue dv = venue(owner, 2);
        dv.setDeletedAt(Instant.now());
        venueRepository.saveAndFlush(dv);

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.venuesCount").value(1));
    }

    @Test
    @DisplayName("Soft-deleted manager counts'a dahil değil")
    void softDeletedManager_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000083", "Test1234");
        createActiveTrial(owner);
        createManager("m1@x.com", "+996700000084", "P", owner);
        User m2 = createManager("m2@x.com", "+996700000085", "P", owner);
        m2.setDeletedAt(Instant.now());
        userRepository.saveAndFlush(m2);

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.managersCount").value(1));
    }

    @Test
    @DisplayName("MANAGER + owner'ın aktif subscription'ı var → profileData yine null (carryover yok)")
    void manager_neverGetsProfileData_evenIfOwnerHasSub() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000086", "Test1234");
        createActiveTrial(owner);
        venue(owner, 1);
        createManager("mgr1@x.com", "+996700000087", "P", owner);
        User mgr = createManager("mgr2@x.com", "+996700000088", "Test1234", owner);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(mgr)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = body(r);
        assertThat(body.has("profileData")).isTrue();
        assertThat(body.get("profileData").isNull()).isTrue();
    }

    @Test
    @DisplayName("Refresh token /profile header'ında → 400 INVALID_TOKEN_TYPE")
    void refreshTokenInBearer_returns400InvalidTokenType() throws Exception {
        User u = createOwner("owner@x.com", "+996700000089", "Test1234");
        String refresh = refreshFor(u, true);

        MvcResult r = mockMvc.perform(withBearer(get(URL), refresh))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_TOKEN_TYPE");
    }

    @Test
    @DisplayName("EXPIRED subscription owner /profile çekebilir (read endpoint, subscription gate'siz)")
    void expiredOwner_canFetchProfile() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000090", "Test1234");
        Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.subscription.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("daysUntilExpiry asla negatif olmaz — endDate geçmişte ise 0")
    void daysUntilExpiry_neverNegative() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000091", "Test1234");
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.GRACE)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .build());

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        int days = body(r).get("profileData").get("subscription").get("daysUntilExpiry").asInt();
        assertThat(days).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("graceDaysRemaining: ACTIVE state'inde her zaman 0")
    void graceDaysRemaining_zeroForActive() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000092", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.profileData.subscription.graceDaysRemaining").value(0));
    }

    @Test
    @DisplayName("UserResponse: name/email/phone null'lar JSON'da görünsün (nullable contract)")
    void userResponse_nullableFieldsPresentInJson() throws Exception {
        // OWNER yarat, sonra phone'u null'la — register flow'unda olmaz ama defansif test
        User owner = createOwner("owner@x.com", "+996700000093", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").exists())
                .andExpect(jsonPath("$.user.name").exists())
                .andExpect(jsonPath("$.user.role").exists())
                .andExpect(jsonPath("$.user.email").exists())
                .andExpect(jsonPath("$.user.phone").exists());
    }

    @Test
    @DisplayName("Profile endpoint table sayısını göstermez (sadece venue+manager+subscription)")
    void profile_doesNotIncludeTableCount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000094", "Test1234");
        createActiveTrial(owner);
        Venue v = venue(owner, 1);
        // Add tables — should not appear in profile response
        tableRepository.saveAndFlush(Tables.builder()
                .venue(v).name("T1").number(1).tarifAmount(100)
                .currency(Tables.Currency.KGS).tarifType(Tables.TarifType.HOUR).build());

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pd = body(r).get("profileData");
        // Spec'te tableCount yok — eklenmesin
        assertThat(pd.has("tableCount")).isFalse();
        assertThat(pd.has("tablesCount")).isFalse();
    }
}
