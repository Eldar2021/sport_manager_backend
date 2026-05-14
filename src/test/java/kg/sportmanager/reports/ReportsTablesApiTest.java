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

class ReportsTablesApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/tables";

    private String url(String venueId, String period, Instant from, Instant to, Boolean compare) {
        StringBuilder sb = new StringBuilder(BASE);
        sb.append("?venueId=").append(venueId);
        sb.append("&period=").append(period);
        sb.append("&from=").append(from);
        sb.append("&to=").append(to);
        if (compare != null) sb.append("&compare=").append(compare);
        return sb.toString();
    }

    @Test
    @DisplayName("Tables revenue DESC, tableNumber ASC sıralı döner")
    void sortedByRevenueDesc() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001300", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t1 = createTable(v, "T1", 1, 100, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 100, Tables.TarifType.HOUR);
        Tables t3 = createTable(v, "T3", 3, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        // t3 = 500, t1 = 200, t2 = 0
        completedSession(t1, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 200);
        completedSession(t3, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 500);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tableId").value(t3.getId().toString()))
                .andExpect(jsonPath("$[0].revenue").value(500))
                .andExpect(jsonPath("$[1].tableId").value(t1.getId().toString()))
                .andExpect(jsonPath("$[1].revenue").value(200))
                .andExpect(jsonPath("$[2].tableId").value(t2.getId().toString()))
                .andExpect(jsonPath("$[2].revenue").value(0));
    }

    @Test
    @DisplayName("compare=true → deltaPercent dolu (prev > 0)")
    void compareTrue_deltaPercentCalculated() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001301", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        // Current: 110
        completedSession(t, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 110);
        // Prev clipped: 100 → delta = +10%
        Instant prevFrom = Instant.parse("2026-04-01T00:00:00Z");
        completedSession(t, owner, prevFrom.plus(1, ChronoUnit.HOURS), prevFrom.plus(2, ChronoUnit.HOURS), 100);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, true), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revenue").value(110))
                .andExpect(jsonPath("$[0].deltaPercent").value(10));
    }

    @Test
    @DisplayName("compare=false → deltaPercent null")
    void compareFalse_deltaNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001302", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        completedSession(t, owner, from.plus(1, ChronoUnit.HOURS), from.plus(2, ChronoUnit.HOURS), 100);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deltaPercent").doesNotExist());
    }

    @Test
    @DisplayName("Soft-deleted tablo listede yok")
    void deletedTable_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001303", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "Active", 1, 100, Tables.TarifType.HOUR);
        createDeletedTable(v, 2);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "MONTH", from, to, false), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tableName").value("Active"));
    }
}
