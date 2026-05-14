package kg.sportmanager.reports;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RevenueSeriesApiTest extends ReportsTestSupport {

    private static final String URL = "/api/v1/reports/revenue-series";

    private String buildUrl(Venue v, String period, Instant from, Instant to) {
        return URL + "?venueId=" + v.getId()
                + "&period=" + period
                + "&from=" + from
                + "&to=" + to;
    }

    @Test
    @DisplayName("WEEK: günlük bucket'lar, boş günler revenue=0 ile dahil")
    void week_dailyBuckets_zeroFilled() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000540", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-07T00:00:00Z");
        Instant to = Instant.parse("2026-05-14T00:00:00Z");
        // Sadece day 2 ve day 5'te session
        completedSession(t, owner, from.plus(2, ChronoUnit.DAYS).plusSeconds(3600),
                from.plus(2, ChronoUnit.DAYS).plusSeconds(7200), 100L);
        completedSession(t, owner, from.plus(5, ChronoUnit.DAYS).plusSeconds(3600),
                from.plus(5, ChronoUnit.DAYS).plusSeconds(7200), 200L);

        MvcResult r = mockMvc.perform(getWithBearer(buildUrl(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(7); // 7 günlük bucket
        // İlk bucket — günün 00:00'ı (from'la aynı)
        assertThat(body.get(0).get("bucket").asText()).startsWith("2026-05-07T00:00:00");
        assertThat(body.get(0).get("revenue").asLong()).isEqualTo(0);
        assertThat(body.get(2).get("revenue").asLong()).isEqualTo(100);
        assertThat(body.get(5).get("revenue").asLong()).isEqualTo(200);
    }

    @Test
    @DisplayName("YEAR: aylık bucket'lar")
    void year_monthlyBuckets() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000541", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        // Şubat ortasında session
        completedSession(t, owner, Instant.parse("2026-02-15T10:00:00Z"),
                Instant.parse("2026-02-15T11:00:00Z"), 500L);

        MvcResult r = mockMvc.perform(getWithBearer(buildUrl(v, "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(3); // Jan, Feb, Mar
        // 0=Jan, 1=Feb, 2=Mar
        assertThat(body.get(0).get("revenue").asLong()).isEqualTo(0);
        assertThat(body.get(1).get("revenue").asLong()).isEqualTo(500);
        assertThat(body.get(2).get("revenue").asLong()).isEqualTo(0);
    }

    @Test
    @DisplayName("TODAY tek bucket olarak döner")
    void today_singleBucket() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000542", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-14T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");
        completedSession(t, owner, from.plusSeconds(36000), from.plusSeconds(39600), 250L);

        MvcResult r = mockMvc.perform(getWithBearer(buildUrl(v, "TODAY", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(1);
        assertThat(body(r).get(0).get("revenue").asLong()).isEqualTo(250);
    }

    @Test
    @DisplayName("Range dışı session sayılmaz")
    void outOfRange_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000543", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-07T00:00:00Z");
        Instant to = Instant.parse("2026-05-14T00:00:00Z");
        // Range öncesi
        completedSession(t, owner, from.minus(2, ChronoUnit.DAYS), from.minus(2, ChronoUnit.DAYS).plusSeconds(3600), 999L);
        // Range sonrası
        completedSession(t, owner, to.plusSeconds(3600), to.plusSeconds(7200), 888L);

        MvcResult r = mockMvc.perform(getWithBearer(buildUrl(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        long totalSum = 0;
        for (JsonNode point : body(r)) totalSum += point.get("revenue").asLong();
        assertThat(totalSum).isEqualTo(0L);
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000544", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000545", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", Instant.parse("2026-05-07T00:00:00Z"),
                                Instant.parse("2026-05-14T00:00:00Z")),
                        accessFor(mgr)))
                .andExpect(status().isForbidden());
    }
}
