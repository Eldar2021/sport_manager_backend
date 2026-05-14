package kg.sportmanager.reports;

import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsManagerDetailApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/managers/";

    private String url(String managerId, String venueId, Instant from, Instant to) {
        return BASE + managerId + "?venueId=" + venueId + "&period=MONTH"
                + "&from=" + from + "&to=" + to;
    }

    @Test
    @DisplayName("Manager detayı: summary + sessionLog (startedAt DESC, max 40)")
    void managerDetail_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001600", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001601", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        // 3 COMPLETED + 1 CANCELLED for mgr
        completedSession(t, mgr, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 100);
        completedSession(t, mgr, from.plus(3, ChronoUnit.HOURS), from.plus(4, ChronoUnit.HOURS), 150);
        completedSession(t, mgr, from.plus(5, ChronoUnit.HOURS), from.plus(6, ChronoUnit.HOURS), 200);
        cancelledSession(t, mgr, from.plus(7, ChronoUnit.HOURS), "yanlış");

        mockMvc.perform(getWithBearer(url(mgr.getId().toString(), v.getId().toString(), from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.managerId").value(mgr.getId().toString()))
                .andExpect(jsonPath("$.summary.name").value("Test Manager"))
                .andExpect(jsonPath("$.summary.revenue").value(450))
                .andExpect(jsonPath("$.summary.sessions").value(3))
                .andExpect(jsonPath("$.summary.cancelCount").value(1))
                .andExpect(jsonPath("$.sessionLog.length()").value(4))
                // En son başlatılan (07:00) en başta:
                .andExpect(jsonPath("$.sessionLog[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.sessionLog[0].cancelReason").value("yanlış"))
                .andExpect(jsonPath("$.sessionLog[3].status").value("COMPLETED"))
                .andExpect(jsonPath("$.sessionLog[3].totalAmount").value(100));
    }

    @Test
    @DisplayName("BUG: Owner kendi session'larını açtıysa /managers/{owner.id} → 200 (404 dönmemeli)")
    void ownerAsManagerInOwnSessions_returns200() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001602", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        completedSession(t, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 250);

        // Owner kendi ID'sini gönderir — onun manager_id'si olarak açtığı session var
        mockMvc.perform(getWithBearer(url(owner.getId().toString(), v.getId().toString(), from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.managerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.summary.revenue").value(250))
                .andExpect(jsonPath("$.summary.sessions").value(1))
                .andExpect(jsonPath("$.sessionLog.length()").value(1));
    }

    @Test
    @DisplayName("Bilinmeyen manager ID → 404 MANAGER_NOT_FOUND")
    void unknownManager_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001603", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        MvcResult r = mockMvc.perform(getWithBearer(
                url(java.util.UUID.randomUUID().toString(), v.getId().toString(), from, to),
                accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "MANAGER_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın manager'ı → 404 MANAGER_NOT_FOUND")
    void otherOwnersManager_returns404() throws Exception {
        User owner1 = createOwner("o1@x.com", "+996700001604", "Test1234");
        createActiveTrial(owner1);
        User owner2 = createOwner("o2@x.com", "+996700001605", "Test1234");
        createActiveTrial(owner2);
        User othersMgr = createManager("mgr@x.com", "+996700001606", "Test1234", owner2);
        Venue v1 = createVenue(owner1, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(
                url(othersMgr.getId().toString(), v1.getId().toString(), from, to),
                accessFor(owner1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001607", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001608", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(mgr.getId().toString(), v.getId().toString(), from, to), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Soft-deleted manager → 404 MANAGER_NOT_FOUND (tarihsel session olsa bile)")
    void softDeletedManager_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001609", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001610", "Test1234", owner);
        mgr.setDeletedAt(Instant.now());
        userRepository.saveAndFlush(mgr);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(mgr.getId().toString(), v.getId().toString(), from, to), accessFor(owner)))
                .andExpect(status().isNotFound());
    }
}
