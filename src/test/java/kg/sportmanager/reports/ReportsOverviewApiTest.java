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

class ReportsOverviewApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/overview";

    private String url(String venueId, String period, Instant from, Instant to, Boolean compare) {
        StringBuilder sb = new StringBuilder(BASE);
        sb.append("?venueId=").append(venueId);
        sb.append("&period=").append(period);
        sb.append("&from=").append(from.toString());
        sb.append("&to=").append(to.toString());
        if (compare != null) sb.append("&compare=").append(compare);
        return sb.toString();
    }

    @Test
    @DisplayName("OWNER + COMPLETED sessions → totalRevenue/totalSessions doğru hesaplar")
    void completedSessions_aggregateCorrectly() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001100", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        // 3 COMPLETED sessions in range
        completedSession(t, owner, from.plus(1, ChronoUnit.DAYS), from.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 250);
        completedSession(t, owner, from.plus(2, ChronoUnit.DAYS), from.plus(2, ChronoUnit.DAYS).plusSeconds(7200), 500);
        completedSession(t, owner, from.plus(3, ChronoUnit.DAYS), from.plus(3, ChronoUnit.DAYS).plusSeconds(1800), 125);
        // 1 CANCELLED in range
        cancelledSession(t, owner, from.plus(4, ChronoUnit.DAYS), "test");
        // 1 COMPLETED OUTSIDE range
        completedSession(t, owner, from.minus(1, ChronoUnit.DAYS), from.minus(1, ChronoUnit.DAYS).plusSeconds(3600), 9999);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(875))           // 250+500+125
                .andExpect(jsonPath("$.totalSessions").value(3))
                .andExpect(jsonPath("$.cancelledSessions").value(1))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.previous").doesNotExist());
    }

    @Test
    @DisplayName("compare=true + period=MONTH → previous (clipped) block döner")
    void compareTrue_returnsClippedPrevious() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001101", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");                 // 14 days current
        // Current period: 1 session 100 KGS
        completedSession(t, owner, from.plus(1, ChronoUnit.DAYS), from.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 100);
        // Previous calendar period (April), within clipped previous (first 14 days)
        Instant prevStart = Instant.parse("2026-04-01T00:00:00Z");
        completedSession(t, owner, prevStart.plus(5, ChronoUnit.DAYS), prevStart.plus(5, ChronoUnit.DAYS).plusSeconds(3600), 80);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, true), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(100))
                .andExpect(jsonPath("$.previous").exists())
                .andExpect(jsonPath("$.previous.totalRevenue").value(80))
                .andExpect(jsonPath("$.previous.previous").doesNotExist());
    }

    @Test
    @DisplayName("period=TODAY → previous her zaman null (compare ignore)")
    void today_previousIsAlwaysNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001102", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant to = from.plus(1, ChronoUnit.DAYS);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "TODAY", from, to, true), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previous").doesNotExist());
    }

    @Test
    @DisplayName("Boş venue → tüm değerler 0")
    void emptyVenue_returnsZeros() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001103", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(0))
                .andExpect(jsonPath("$.totalSessions").value(0))
                .andExpect(jsonPath("$.cancelledSessions").value(0));
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404 VENUE_NOT_FOUND")
    void unknownVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001104", "Test1234");
        createActiveTrial(owner);
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        MvcResult r = mockMvc.perform(getWithBearer(
                url(java.util.UUID.randomUUID().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın venue'i → 404 VENUE_NOT_FOUND (info-leak yok)")
    void otherOwnersVenue_returns404() throws Exception {
        User owner1 = createOwner("o1@x.com", "+996700001105", "Test1234");
        createActiveTrial(owner1);
        User owner2 = createOwner("o2@x.com", "+996700001106", "Test1234");
        createActiveTrial(owner2);
        Venue otherVenue = createVenue(owner2, "Other", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(
                url(otherVenue.getId().toString(), "MONTH", from, to, false), accessFor(owner1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001107", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001108", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Eksik query param venueId → 400/422")
    void missingVenueId_returns4xx() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001109", "Test1234");
        createActiveTrial(owner);
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(
                BASE + "?period=MONTH&from=" + from + "&to=" + to, accessFor(owner)))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s < 400 || s >= 500) throw new AssertionError("Expected 4xx, got " + s);
                });
    }

    @Test
    @DisplayName("Bozuk venueId UUID → 422 VALIDATION_ERROR")
    void badUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001110", "Test1234");
        createActiveTrial(owner);
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(
                url("not-a-uuid", "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Range dışındaki sessionlar dahil edilmez")
    void sessionsOutsideRange_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001111", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        // Boundary edge cases:
        // Exactly at "from" → included (>= from)
        completedSession(t, owner, from, from.plusSeconds(3600), 100);
        // Exactly at "to" → excluded (< to)
        completedSession(t, owner, to, to.plusSeconds(3600), 999);
        // Just before "to" → included
        completedSession(t, owner, to.minusSeconds(1), to.minusSeconds(1).plusSeconds(60), 50);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(150))
                .andExpect(jsonPath("$.totalSessions").value(2));
    }
}
