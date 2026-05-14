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

class OverviewApiTest extends ReportsTestSupport {

    private static final String URL = "/api/v1/reports/overview";

    /** Current period: 7 days ending at fixed `to`. */
    private final Instant TO   = Instant.parse("2026-05-14T00:00:00Z");
    private final Instant FROM = Instant.parse("2026-05-07T00:00:00Z");

    private String buildUrl(Venue v, String period, Instant from, Instant to, Boolean compare) {
        StringBuilder s = new StringBuilder(URL).append("?venueId=").append(v.getId())
                .append("&period=").append(period)
                .append("&from=").append(from)
                .append("&to=").append(to);
        if (compare != null) s.append("&compare=").append(compare);
        return s.toString();
    }

    @Test
    @DisplayName("Range içindeki COMPLETED revenue/sessions toplanır; CANCELLED ayrı cancelledSessions sayar")
    void revenueAndSessions_summed() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000520", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant inRange = FROM.plus(2, ChronoUnit.DAYS);
        completedSession(t, owner, inRange, inRange.plusSeconds(3600), 250L);
        completedSession(t, owner, inRange.plusSeconds(3700), inRange.plusSeconds(7300), 250L);
        cancelledSession(t, owner, inRange.plusSeconds(7400), "no-show");

        // Range dışı (öncesi)
        completedSession(t, owner, FROM.minus(2, ChronoUnit.DAYS), FROM.minus(1, ChronoUnit.DAYS), 999L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, true), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.get("totalRevenue").asLong()).isEqualTo(500L);
        assertThat(body.get("totalSessions").asLong()).isEqualTo(2L);
        assertThat(body.get("cancelledSessions").asLong()).isEqualTo(1L);
        assertThat(body.get("currency").asText()).isEqualTo("KGS");
    }

    @Test
    @DisplayName("compare=true & period!=TODAY → previous block dolar (clipped previous)")
    void compare_returnsPreviousBlock() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000521", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        // Current week
        completedSession(t, owner, FROM.plus(1, ChronoUnit.DAYS), FROM.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 300L);
        // Previous week (clipped: -7 günden -7+N güne) — FROM-7d ile FROM aralığı
        Instant prevFrom = FROM.minus(7, ChronoUnit.DAYS);
        completedSession(t, owner, prevFrom.plus(1, ChronoUnit.DAYS), prevFrom.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 100L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, true), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.get("totalRevenue").asLong()).isEqualTo(300L);
        assertThat(body.get("previous").isNull()).isFalse();
        assertThat(body.get("previous").get("totalRevenue").asLong()).isEqualTo(100L);
        // previous.previous her zaman null
        assertThat(body.get("previous").get("previous").isNull()).isTrue();
    }

    @Test
    @DisplayName("compare=false → previous=null")
    void compareFalse_previousIsNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000522", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("previous").isNull()).isTrue();
    }

    @Test
    @DisplayName("period=TODAY → previous=null (compare true bile olsa)")
    void today_previousIsNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000523", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "TODAY", FROM, FROM.plus(1, ChronoUnit.DAYS), true),
                        accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("previous").isNull()).isTrue();
    }

    @Test
    @DisplayName("Veri yok → tüm sayılar 0 (boş response değil)")
    void noData_zeros() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000524", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = body(r);
        assertThat(body.get("totalRevenue").asLong()).isEqualTo(0L);
        assertThat(body.get("totalSessions").asLong()).isEqualTo(0L);
        assertThat(body.get("cancelledSessions").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Range exclusive: tam `to` saniyesindeki session sayılmaz")
    void rangeIsExclusiveOnTo() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000525", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        completedSession(t, owner, TO, TO.plusSeconds(3600), 100L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("totalRevenue").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404 VENUE_NOT_FOUND")
    void unknownVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000526", "Test1234");
        createActiveTrial(owner);
        String fakeId = java.util.UUID.randomUUID().toString();

        MvcResult r = mockMvc.perform(getWithBearer(
                        URL + "?venueId=" + fakeId + "&period=WEEK&from=" + FROM + "&to=" + TO,
                        accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın venue'su → 404 VENUE_NOT_FOUND")
    void otherOwnersVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000527", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000528", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "Other", 1, true);

        mockMvc.perform(getWithBearer(buildUrl(otherVenue, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000529", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000530", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(buildUrl(v, "WEEK", FROM, TO, false), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Currency: table'ın currency'sinden alınır (varsayılan KGS)")
    void currency_fromFirstTable() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000531", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = tableRepository.saveAndFlush(Tables.builder()
                .venue(v).name("T").number(1)
                .tarifAmount(100).currency(Tables.Currency.USD)
                .tarifType(Tables.TarifType.HOUR).build());

        MvcResult r = mockMvc.perform(getWithBearer(
                        buildUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("currency").asText()).isEqualTo("USD");
    }
}
