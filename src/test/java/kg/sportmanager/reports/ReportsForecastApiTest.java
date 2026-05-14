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

class ReportsForecastApiTest extends ReportsTestSupport {

    private static final String BASE = "/api/v1/reports/forecast";

    private String url(String venueId, String period, Instant from, Instant to) {
        return BASE + "?venueId=" + venueId + "&period=" + period
                + "&from=" + from + "&to=" + to;
    }

    @Test
    @DisplayName("BUG: WEEK period 4 günlük range → NOT_ENOUGH_DATA dönmemeli, 200 + projection")
    void week_4DayRange_doesNotReturnNotEnoughData() throws Exception {
        // Kullanıcının bildirdiği bug senaryosu:
        // period=WEEK, from=2026-05-10T18:00, to=2026-05-14T18:00 (4 gün) → 422 NOT_ENOUGH_DATA
        // Beklenen: linear regression için 2 nokta yeter; 4 günlük seri yeterli olmalı
        User owner = createOwner("owner@x.com", "+996700001700", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-10T18:00:00Z");
        Instant to = Instant.parse("2026-05-14T18:00:00Z");
        // Her gün biraz revenue ekle
        for (int day = 0; day < 4; day++) {
            Instant st = from.plus(day, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
            completedSession(t, owner, st, st.plus(1, ChronoUnit.HOURS), 100 + day * 10L);
        }

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.projectedTotal").isNumber())
                .andExpect(jsonPath("$.currency").value("KGS"));
    }

    @Test
    @DisplayName("Veri var ama < 2 bucket → NOT_ENOUGH_DATA (regression için minimum 2 nokta)")
    void onlyOneBucket_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001701", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-14T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");                  // 1 gün → 1 bucket

        MvcResult r = mockMvc.perform(getWithBearer(url(v.getId().toString(), "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(body(r), "NOT_ENOUGH_DATA");
    }

    @Test
    @DisplayName("Forecast points: actual (isProjection=false) + projection (isProjection=true) birlikte")
    void forecast_actualAndProjectionPoints() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001702", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        // WEEK = bu hafta Mon → Pzt+7
        Instant from = Instant.parse("2026-05-11T00:00:00Z");                // Monday
        Instant to = Instant.parse("2026-05-14T00:00:00Z");                  // Thursday (3 gün geçti)

        // 3 gün için günlük revenue
        for (int day = 0; day < 3; day++) {
            Instant st = from.plus(day, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
            completedSession(t, owner, st, st.plus(1, ChronoUnit.HOURS), 100 + day * 50L);
        }

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].isProjection").value(false))
                .andExpect(jsonPath("$.points[0].expected").value(100))
                .andExpect(jsonPath("$.points[1].isProjection").value(false))
                .andExpect(jsonPath("$.points[1].expected").value(150))
                .andExpect(jsonPath("$.points[2].isProjection").value(false))
                .andExpect(jsonPath("$.points[2].expected").value(200))
                // 3 fiili gün + 4 projection gün (Thu, Fri, Sat, Sun)
                .andExpect(jsonPath("$.points[3].isProjection").value(true))
                .andExpect(jsonPath("$.points[6].isProjection").value(true));
    }

    @Test
    @DisplayName("previousPeriodTotal: full previous period (clipped DEĞİL)")
    void previousPeriodTotal_isFullPrevious() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001703", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        // Current: WEEK May 11-18 (Mon-Sun), but range only 3 days (May 11-14)
        Instant from = Instant.parse("2026-05-11T00:00:00Z");
        Instant to = Instant.parse("2026-05-14T00:00:00Z");
        for (int day = 0; day < 3; day++) {
            Instant st = from.plus(day, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
            completedSession(t, owner, st, st.plus(1, ChronoUnit.HOURS), 100);
        }

        // Previous WEEK (May 4-11). Add revenue spread over 5 days = 500 total.
        Instant prevFrom = Instant.parse("2026-05-04T00:00:00Z");
        for (int day = 0; day < 5; day++) {
            Instant st = prevFrom.plus(day, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
            completedSession(t, owner, st, st.plus(1, ChronoUnit.HOURS), 100);
        }

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousPeriodTotal").value(500));
    }

    @Test
    @DisplayName("Bilinmeyen venue → 404 VENUE_NOT_FOUND")
    void unknownVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001704", "Test1234");
        createActiveTrial(owner);
        Instant from = Instant.parse("2026-05-11T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(
                url(java.util.UUID.randomUUID().toString(), "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001705", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001706", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        Instant from = Instant.parse("2026-05-11T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "WEEK", from, to), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("YEAR period: minimum 2 ay bucket lazım")
    void year_below2Months_returnsNotEnoughData() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001707", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        // Year period but range is < 1 month → 1 monthly bucket
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("YEAR period 3 aylık range → 200 + projection")
    void year_3Months_returnsForecast() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001708", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");                  // 3 months
        completedSession(t, owner, Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T01:00:00Z"), 100);
        completedSession(t, owner, Instant.parse("2026-02-15T00:00:00Z"), Instant.parse("2026-02-15T01:00:00Z"), 150);
        completedSession(t, owner, Instant.parse("2026-03-15T00:00:00Z"), Instant.parse("2026-03-15T01:00:00Z"), 200);

        mockMvc.perform(getWithBearer(url(v.getId().toString(), "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.projectedTotal").isNumber());
    }
}
