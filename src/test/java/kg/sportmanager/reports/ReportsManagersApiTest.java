package kg.sportmanager.reports;

import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsManagersApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/managers";

    private String url(String venueId, String period, Instant from, Instant to) {
        return BASE + "?venueId=" + venueId + "&period=" + period
                + "&from=" + from + "&to=" + to;
    }

    @Test
    @DisplayName("Manager listesi revenue DESC sıralı; iptal sayısı raporda görünür")
    void managers_sortedByRevenue() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001500", "Test1234");
        createActiveTrial(owner);
        User m1 = createManager("m1@x.com", "+996700001501", "Test1234", owner);
        User m2 = createManager("m2@x.com", "+996700001502", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        // m1 → 300 revenue, 2 cancels
        completedSession(t, m1, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 300);
        cancelledSession(t, m1, from.plus(3, ChronoUnit.HOURS), "x");
        cancelledSession(t, m1, from.plus(4, ChronoUnit.HOURS), "y");
        // m2 → 500 revenue, 0 cancel
        completedSession(t, m2, from.plus(5, ChronoUnit.HOURS), from.plus(6, ChronoUnit.HOURS), 500);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].managerId").value(m2.getId().toString()))
                .andExpect(jsonPath("$[0].revenue").value(500))
                .andExpect(jsonPath("$[0].sessions").value(1))
                .andExpect(jsonPath("$[0].cancelCount").value(0))
                .andExpect(jsonPath("$[1].managerId").value(m1.getId().toString()))
                .andExpect(jsonPath("$[1].revenue").value(300))
                .andExpect(jsonPath("$[1].cancelCount").value(2));
    }

    @Test
    @DisplayName("Owner kendi session'larını açtıysa owner da listede görünür")
    void ownerWhoStartsSessions_appearsInList() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001503", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        completedSession(t, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 250);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].managerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$[0].revenue").value(250));
    }

    @Test
    @DisplayName("Hiç session yok → boş array")
    void noSessions_returnsEmpty() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001504", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001505", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001506", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }
}
