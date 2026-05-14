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

class ReportsTableDetailApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/tables/";

    private String url(String tableId, String venueId, String period, Instant from, Instant to) {
        return BASE + tableId + "?venueId=" + venueId + "&period=" + period
                + "&from=" + from + "&to=" + to;
    }

    @Test
    @DisplayName("Tablo detayı: summary + revenueSeries + 7x24 heatmap")
    void tableDetail_returnsFullStructure() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001400", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-04T00:00:00Z");

        // May 1 Friday (day-of-week=4 in ISO Mon=0..Sun=6) at 12:00 UTC, 100 KGS
        Instant s1Start = Instant.parse("2026-05-01T12:00:00Z");
        completedSession(t, owner, s1Start, s1Start.plus(1, ChronoUnit.HOURS), 100);

        mockMvc.perform(getWithBearer(url(t.getId().toString(), v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.tableId").value(t.getId().toString()))
                .andExpect(jsonPath("$.summary.revenue").value(100))
                .andExpect(jsonPath("$.summary.sessions").value(1))
                .andExpect(jsonPath("$.revenueSeries.length()").value(3))
                .andExpect(jsonPath("$.revenueSeries[0].revenue").value(100))
                .andExpect(jsonPath("$.hourHeatmap.length()").value(7))
                .andExpect(jsonPath("$.hourHeatmap[0].length()").value(24));
    }

    @Test
    @DisplayName("Heatmap: ISO Mon=0..Sun=6, hour=0..23, totalAmount kümülatif")
    void heatmap_correctIndices() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001401", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-08T00:00:00Z");
        // May 1 2026 is Friday (ISO DoW = 5 → index 4)
        // Two sessions at Friday 12:00 — sum to heatmap[4][12]
        Instant fri12 = Instant.parse("2026-05-01T12:00:00Z");
        completedSession(t, owner, fri12, fri12.plus(1, ChronoUnit.HOURS), 100);
        completedSession(t, owner, fri12.plus(30, ChronoUnit.MINUTES),
                fri12.plus(90, ChronoUnit.MINUTES), 50);

        // Saturday 09:00 (ISO DoW = 6 → index 5)
        Instant sat9 = Instant.parse("2026-05-02T09:00:00Z");
        completedSession(t, owner, sat9, sat9.plus(1, ChronoUnit.HOURS), 75);

        mockMvc.perform(getWithBearer(url(t.getId().toString(), v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourHeatmap[4][12]").value(150))      // Fri 12h sum
                .andExpect(jsonPath("$.hourHeatmap[5][9]").value(75))        // Sat 9h
                .andExpect(jsonPath("$.hourHeatmap[0][0]").value(0));        // Mon 0h boş
    }

    @Test
    @DisplayName("Bilinmeyen table id → 404 TABLE_NOT_FOUND")
    void unknownTable_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001402", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-04T00:00:00Z");

        MvcResult r = mockMvc.perform(getWithBearer(
                url(java.util.UUID.randomUUID().toString(), v.getId().toString(), "MONTH", from, to),
                accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Table başka venue'a aitse → 404 TABLE_NOT_FOUND (data tutarsızlığı önleme)")
    void tableInDifferentVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001403", "Test1234");
        createActiveTrial(owner);
        Venue v1 = createVenue(owner, "V1", 1, true);
        Venue v2 = createVenue(owner, "V2", 2, false);
        Tables t = createTable(v2, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-04T00:00:00Z");

        MvcResult r = mockMvc.perform(getWithBearer(
                url(t.getId().toString(), v1.getId().toString(), "MONTH", from, to),
                accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın table'ı → 403 FORBIDDEN")
    void otherOwnersTable_returns403() throws Exception {
        User owner1 = createOwner("o1@x.com", "+996700001404", "Test1234");
        createActiveTrial(owner1);
        User owner2 = createOwner("o2@x.com", "+996700001405", "Test1234");
        createActiveTrial(owner2);
        Venue v2 = createVenue(owner2, "V", 1, true);
        Tables t2 = createTable(v2, "T", 1, 100, Tables.TarifType.HOUR);
        Venue v1 = createVenue(owner1, "V1", 1, true);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-04T00:00:00Z");

        mockMvc.perform(getWithBearer(
                url(t2.getId().toString(), v1.getId().toString(), "MONTH", from, to),
                accessFor(owner1)))
                .andExpect(status().isForbidden());
    }
}
