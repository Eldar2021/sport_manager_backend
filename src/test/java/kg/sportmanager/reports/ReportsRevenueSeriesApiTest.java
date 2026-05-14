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

class ReportsRevenueSeriesApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/revenue-series";

    private String url(String venueId, String period, Instant from, Instant to) {
        return BASE + "?venueId=" + venueId + "&period=" + period
                + "&from=" + from + "&to=" + to;
    }

    @Test
    @DisplayName("MONTH (15 days) → 15 günlük bucket dizisi (boş günler 0)")
    void month_returnsDailyBuckets_includingEmpty() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001200", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-16T00:00:00Z");

        // Day 1 (May 1): 200 KGS
        completedSession(t, owner, from.plus(2, ChronoUnit.HOURS), from.plus(3, ChronoUnit.HOURS), 200);
        // Day 5 (May 5): 150 KGS + 50 KGS
        completedSession(t, owner, from.plus(4, ChronoUnit.DAYS), from.plus(4, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS), 150);
        completedSession(t, owner, from.plus(4, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                from.plus(4, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS), 50);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$[0].revenue").value(200))
                .andExpect(jsonPath("$[0].sessions").value(1))
                .andExpect(jsonPath("$[1].revenue").value(0))                // May 2 boş
                .andExpect(jsonPath("$[4].revenue").value(200))               // May 5
                .andExpect(jsonPath("$[4].sessions").value(2));
    }

    @Test
    @DisplayName("YEAR → aylık bucket'lar")
    void year_returnsMonthlyBuckets() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001201", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");                  // 3 months

        // Jan: 100
        completedSession(t, owner, from.plus(5, ChronoUnit.DAYS), from.plus(5, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS), 100);
        // Feb: 200
        completedSession(t, owner, Instant.parse("2026-02-15T00:00:00Z"), Instant.parse("2026-02-15T01:00:00Z"), 200);
        // Mar: 300
        completedSession(t, owner, Instant.parse("2026-03-15T00:00:00Z"), Instant.parse("2026-03-15T01:00:00Z"), 300);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].revenue").value(100))
                .andExpect(jsonPath("$[1].revenue").value(200))
                .andExpect(jsonPath("$[2].revenue").value(300));
    }

    @Test
    @DisplayName("Hiç session yok → tüm bucket'lar 0")
    void noSessions_returnsZeroes() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001202", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-04T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].revenue").value(0))
                .andExpect(jsonPath("$[0].sessions").value(0))
                .andExpect(jsonPath("$[1].revenue").value(0))
                .andExpect(jsonPath("$[2].revenue").value(0));
    }

    @Test
    @DisplayName("CANCELLED sessionlar revenue'a dahil edilmez")
    void cancelledSessions_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001203", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-03T00:00:00Z");

        completedSession(t, owner, from.plus(2, ChronoUnit.HOURS), from.plus(3, ChronoUnit.HOURS), 100);
        cancelledSession(t, owner, from.plus(4, ChronoUnit.HOURS), "test cancel");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revenue").value(100))
                .andExpect(jsonPath("$[0].sessions").value(1));
    }
}
