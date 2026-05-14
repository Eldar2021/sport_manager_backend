package kg.sportmanager.managers;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagersDeleteApiTest extends ManagersTestSupport {

    private static final String BASE = "/api/v1/managers/";

    @Test
    @DisplayName("Happy path: OWNER manager'ı siler → 204; soft-delete + refresh temizlenir")
    void delete_happyPath_softDeletesAndClearsRefresh() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002100", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002101", "Test1234", owner);
        refreshFor(mgr, true);                                              // refresh DB'de var

        mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNoContent());

        User reloaded = userRepository.findById(mgr.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("Aktif session varsa → 409 HAS_ACTIVE_SESSION (manager silinmez)")
    void activeSession_returns409_noDelete() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002102", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002103", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);
        createActiveSession(t, mgr);                                        // manager'ın aktif session'ı

        MvcResult r = mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "HAS_ACTIVE_SESSION");

        User reloaded = userRepository.findById(mgr.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();                       // hâlâ aktif
    }

    @Test
    @DisplayName("Manager'ın geçmiş COMPLETED session'ları silmeyi engellemez")
    void completedSessions_doNotBlockDelete() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002104", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002105", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);
        Instant past = Instant.parse("2026-05-01T10:00:00Z");
        completedSession(t, mgr, past, past.plus(1, ChronoUnit.HOURS), 100);

        mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Bilinmeyen ID → 404 MANAGER_NOT_FOUND")
    void unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002106", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(delete(BASE + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "MANAGER_NOT_FOUND");
    }

    @Test
    @DisplayName("Bozuk UUID → 404 MANAGER_NOT_FOUND (info-leak engelleme)")
    void invalidUuid_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002107", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(delete(BASE + "not-a-uuid")
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "MANAGER_NOT_FOUND");
    }

    @Test
    @DisplayName("Multi-tenant: başka owner'ın manager'ı → 404 (leak yok)")
    void otherOwnersManager_returns404() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700002108", "Test1234");
        createActiveTrial(o1);
        User o2 = createOwner("o2@x.com", "+996700002109", "Test1234");
        createActiveTrial(o2);
        User othersMgr = createManager("m2@x.com", "+996700002110", "Test1234", o2);

        mockMvc.perform(delete(BASE + othersMgr.getId())
                        .header("Authorization", "Bearer " + accessFor(o1)))
                .andExpect(status().isNotFound());

        // Doğrula: o2'nin manager'ı hâlâ aktif
        assertThat(userRepository.findById(othersMgr.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Zaten silinmiş manager → 404 (idempotent değil, sade NOT_FOUND)")
    void alreadyDeleted_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002111", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002112", "Test1234", owner);
        mgr.setDeletedAt(Instant.now());
        userRepository.saveAndFlush(mgr);

        mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER rolü DELETE çağırırsa → 403 FORBIDDEN")
    void manager_calling_delete_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002113", "Test1234");
        createActiveTrial(owner);
        User m1 = createManager("m1@x.com", "+996700002114", "Test1234", owner);
        User m2 = createManager("m2@x.com", "+996700002115", "Test1234", owner);

        // m1 m2'yi silmeye çalışır
        mockMvc.perform(delete(BASE + m2.getId())
                        .header("Authorization", "Bearer " + accessFor(m1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Owner kendi-id'sini silemez (owner'ın 'owner' fk'i null → manager lookup başarısız)")
    void owner_cannotSelfDelete() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002116", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(delete(BASE + owner.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED (delete yazma gate'ine takılır)")
    void expiredSubscription_returns403SubscriptionRequired() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002117", "Test1234");
        Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());
        User mgr = createManager("mgr@x.com", "+996700002118", "Test1234", owner);

        MvcResult r = mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED (ne 401 ne 500)")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(delete(BASE + java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Silinen manager'ın silinmiş halinde de geçmiş report'larda görünmesi gerek (audit)")
    void deletedManager_stillVisibleInReports() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002119", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002120", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);
        // Past session attributed to mgr
        Instant past = Instant.parse("2026-05-01T10:00:00Z");
        completedSession(t, mgr, past, past.plus(1, ChronoUnit.HOURS), 200);

        mockMvc.perform(delete(BASE + mgr.getId())
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNoContent());

        // Reports listesinde hala görünür (geçmiş session bağlı)
        String reportsUrl = "/api/v1/reports/managers?venueId=" + v.getId()
                + "&period=MONTH&from=2026-05-01T00:00:00Z&to=2026-05-31T00:00:00Z";
        mockMvc.perform(getWithBearer(reportsUrl, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].managerId").value(mgr.getId().toString()));
    }

    @Test
    @DisplayName("Bozuk Bearer token → 400 INVALID_TOKEN")
    void invalidBearer_returns400() throws Exception {
        mockMvc.perform(delete(BASE + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer not.a.real.jwt"))
                .andExpect(status().isBadRequest());
    }
}
