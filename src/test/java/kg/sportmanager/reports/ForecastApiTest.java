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

class ForecastApiTest extends ReportsTestSupport {

    private String url(Venue v, String period, Instant from, Instant to) {
        return "/api/v1/reports/forecast"
                + "?venueId=" + v.getId()
                + "&period=" + period
                + "&from=" + from
                + "&to=" + to;
    }

    // ─── BUG KULLANICI BİLDİRDİ: WEEK + 4 günlük aralık → 422 dönüyordu ─────────

    @Test
    @DisplayName("KRİTİK BUG: WEEK 4 günlük data → 200 forecast döner (NOT 422)")
    void week_with4days_doesNotReturn422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000700", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        // 4 gün veri
        Instant from = Instant.parse("2026-05-10T00:00:00Z");
        Instant to   = Instant.parse("2026-05-14T00:00:00Z");
        for (int i = 0; i < 4; i++) {
            Instant day = from.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 100L + i * 50L);
        }

        MvcResult r = mockMvc.perform(getWithBearer(url(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.get("points").isArray()).isTrue();
        assertThat(body.get("points").size()).isGreaterThan(0);
        assertThat(body.get("projectedTotal").asLong()).isGreaterThan(0);
        assertThat(body.get("currency").asText()).isEqualTo("KGS");
    }

    @Test
    @DisplayName("WEEK 2 günlük data → 200 (lineer regresyon için 2 nokta yeterli)")
    void week_with2days_succeeds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000701", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-11T00:00:00Z");
        Instant to   = Instant.parse("2026-05-13T00:00:00Z");
        for (int i = 0; i < 2; i++) {
            Instant day = from.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 100L + i * 50L);
        }

        mockMvc.perform(getWithBearer(url(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("WEEK sadece 1 günlük data → 422 NOT_ENOUGH_DATA (regresyon imkansız)")
    void week_with1day_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000702", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-11T00:00:00Z");
        Instant to   = Instant.parse("2026-05-12T00:00:00Z");

        MvcResult r = mockMvc.perform(getWithBearer(url(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(body(r), "NOT_ENOUGH_DATA");
    }

    @Test
    @DisplayName("MONTH: 7 günden az data → 422 NOT_ENOUGH_DATA")
    void month_withLessThan7days_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000703", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-05T00:00:00Z"); // 4 gün

        mockMvc.perform(getWithBearer(url(v, "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("MONTH: 7+ günlük data → 200")
    void month_with7days_succeeds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000704", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-08T00:00:00Z");
        for (int i = 0; i < 7; i++) {
            Instant day = from.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 100L);
        }

        mockMvc.perform(getWithBearer(url(v, "MONTH", from, to), accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("YEAR: 2 aydan az → 422")
    void year_with1month_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000705", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-01-15T00:00:00Z");

        mockMvc.perform(getWithBearer(url(v, "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("YEAR: 2 aylık veri → 200")
    void year_with2months_succeeds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000706", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-01T00:00:00Z");
        completedSession(t, owner, Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T11:00:00Z"), 200L);
        completedSession(t, owner, Instant.parse("2026-02-15T10:00:00Z"),
                Instant.parse("2026-02-15T11:00:00Z"), 250L);

        mockMvc.perform(getWithBearer(url(v, "YEAR", from, to), accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Forecast: actual nokta isProjection=false, gelecek nokta isProjection=true")
    void points_mixOfActualAndProjection() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000707", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-11T00:00:00Z"); // Monday
        Instant to   = Instant.parse("2026-05-14T00:00:00Z"); // 3 gün
        for (int i = 0; i < 3; i++) {
            Instant day = from.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 100L + i * 50L);
        }

        MvcResult r = mockMvc.perform(getWithBearer(url(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode points = body(r).get("points");
        // İlk 3 nokta actual
        long actualCount = 0, projectionCount = 0;
        for (JsonNode p : points) {
            if (p.get("isProjection").asBoolean()) projectionCount++;
            else actualCount++;
        }
        assertThat(actualCount).isEqualTo(3);
        assertThat(projectionCount).isGreaterThan(0); // hafta sonuna kadar projection
    }

    @Test
    @DisplayName("Forecast: previousPeriodTotal — full previous period (clipped DEĞİL)")
    void forecast_previousIsFullPeriod() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000708", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant from = Instant.parse("2026-05-11T00:00:00Z"); // bu Pazartesi
        Instant to   = Instant.parse("2026-05-13T00:00:00Z"); // 2 gün
        // Current week 2 nokta
        for (int i = 0; i < 2; i++) {
            Instant day = from.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 100L);
        }
        // Geçen hafta TÜM 7 günde session var (toplam 7×500 = 3500)
        Instant prevFrom = from.minus(7, ChronoUnit.DAYS);
        for (int i = 0; i < 7; i++) {
            Instant day = prevFrom.plus(i, ChronoUnit.DAYS).plusSeconds(36000);
            completedSession(t, owner, day, day.plusSeconds(3600), 500L);
        }

        MvcResult r = mockMvc.perform(getWithBearer(url(v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        long prevTotal = body(r).get("previousPeriodTotal").asLong();
        // FULL previous → 3500 (clipped olsaydı sadece 2 gün × 500 = 1000 olurdu)
        assertThat(prevTotal).isEqualTo(3500L);
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404")
    void unknownVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000709", "Test1234");
        createActiveTrial(owner);
        String fakeId = java.util.UUID.randomUUID().toString();
        Instant from = Instant.parse("2026-05-10T00:00:00Z");
        Instant to   = Instant.parse("2026-05-14T00:00:00Z");

        mockMvc.perform(getWithBearer(
                        "/api/v1/reports/forecast?venueId=" + fakeId
                                + "&period=WEEK&from=" + from + "&to=" + to,
                        accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000710", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000711", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(
                        url(v, "WEEK", Instant.parse("2026-05-10T00:00:00Z"),
                                Instant.parse("2026-05-14T00:00:00Z")),
                        accessFor(mgr)))
                .andExpect(status().isForbidden());
    }
}
