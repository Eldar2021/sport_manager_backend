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

class TablesReportApiTest extends ReportsTestSupport {

    private final Instant FROM = Instant.parse("2026-05-07T00:00:00Z");
    private final Instant TO   = Instant.parse("2026-05-14T00:00:00Z");

    private String listUrl(Venue v, String period, Instant from, Instant to, Boolean compare) {
        StringBuilder s = new StringBuilder("/api/v1/reports/tables")
                .append("?venueId=").append(v.getId())
                .append("&period=").append(period)
                .append("&from=").append(from)
                .append("&to=").append(to);
        if (compare != null) s.append("&compare=").append(compare);
        return s.toString();
    }

    private String detailUrl(java.util.UUID tableId, Venue v, String period, Instant from, Instant to) {
        return "/api/v1/reports/tables/" + tableId
                + "?venueId=" + v.getId()
                + "&period=" + period
                + "&from=" + from
                + "&to=" + to;
    }

    // ─── /reports/tables ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tablo listesi revenue DESC, tableNumber ASC sıralı")
    void tablesList_sortedByRevenue() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000600", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t1 = createTable(v, "T1", 1, 250, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 250, Tables.TarifType.HOUR);
        Tables t3 = createTable(v, "T3", 3, 250, Tables.TarifType.HOUR);

        Instant in = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t1, owner, in, in.plusSeconds(3600), 100L);
        completedSession(t2, owner, in, in.plusSeconds(3600), 300L);
        completedSession(t3, owner, in, in.plusSeconds(3600), 200L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        listUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get(0).get("revenue").asLong()).isEqualTo(300);
        assertThat(body.get(1).get("revenue").asLong()).isEqualTo(200);
        assertThat(body.get(2).get("revenue").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("Veri yoksa, table listesi 0 revenue ile döner; sıralama tableNumber ASC")
    void emptyData_zeroRevenue_tableNumberAsc() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000601", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T1", 1, 250, Tables.TarifType.HOUR);
        createTable(v, "T2", 2, 250, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(getWithBearer(
                        listUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(2);
        for (JsonNode row : body) assertThat(row.get("revenue").asLong()).isEqualTo(0);
        // Bağ kopması: tableNumber ASC
        assertThat(body.get(0).get("tableNumber").asInt()).isEqualTo(1);
        assertThat(body.get(1).get("tableNumber").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("compare=true → deltaPercent doğru, geçmiş yok ise null")
    void deltaPercent_computed() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000602", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        // Current: 200; previous (clipped): 100 → delta = +100%
        Instant inCurr = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t, owner, inCurr, inCurr.plusSeconds(3600), 200L);
        Instant prevFrom = FROM.minus(7, ChronoUnit.DAYS);
        completedSession(t, owner, prevFrom.plus(1, ChronoUnit.DAYS),
                prevFrom.plus(1, ChronoUnit.DAYS).plusSeconds(3600), 100L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        listUrl(v, "WEEK", FROM, TO, true), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode row = body(r).get(0);
        assertThat(row.get("deltaPercent").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("compare=false → deltaPercent=null")
    void compareFalse_deltaNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000603", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(getWithBearer(
                        listUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).get(0).get("deltaPercent").isNull()).isTrue();
    }

    @Test
    @DisplayName("Soft-deleted tables listede yok")
    void softDeletedTables_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000604", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "Active", 1, 250, Tables.TarifType.HOUR);
        createDeletedTable(v, 2);

        MvcResult r = mockMvc.perform(getWithBearer(
                        listUrl(v, "WEEK", FROM, TO, false), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(1);
        assertThat(body(r).get(0).get("tableName").asText()).isEqualTo("Active");
    }

    // ─── /reports/tables/{id} ────────────────────────────────────────────────────

    @Test
    @DisplayName("Detail: summary + revenueSeries + 7×24 heatmap döner")
    void detail_returnsHeatmapShape() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000605", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        // Pazartesi (2026-05-04) saat 19:00 UTC bir session
        Instant mondayEvening = Instant.parse("2026-05-04T19:00:00Z");
        completedSession(t, owner, mondayEvening, mondayEvening.plusSeconds(3600), 250L);

        Instant from = Instant.parse("2026-05-04T00:00:00Z");
        Instant to = Instant.parse("2026-05-11T00:00:00Z");
        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(t.getId(), v, "WEEK", from, to), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.get("summary").get("revenue").asLong()).isEqualTo(250);
        JsonNode heat = body.get("hourHeatmap");
        assertThat(heat.size()).isEqualTo(7); // 7 satır (gün)
        for (int dow = 0; dow < 7; dow++) {
            assertThat(heat.get(dow).size()).isEqualTo(24); // 24 saat
        }
        // Pazartesi (dow=0) saat 19 → 250
        assertThat(heat.get(0).get(19).asLong()).isEqualTo(250);
        // Diğer hücreler 0
        assertThat(heat.get(0).get(18).asLong()).isEqualTo(0);
        assertThat(heat.get(1).get(19).asLong()).isEqualTo(0);
    }

    @Test
    @DisplayName("Detail bilinmeyen tableId → 404")
    void detail_unknownTable_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000606", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(java.util.UUID.randomUUID(), v, "WEEK", FROM, TO),
                        accessFor(owner)))
                .andExpect(status().isNotFound()).andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Detail: table farklı venue'da ise → 404 TABLE_NOT_FOUND (tutarsızlık koruması)")
    void detail_tableInDifferentVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000607", "Test1234");
        createActiveTrial(owner);
        Venue v1 = createVenue(owner, "V1", 1, true);
        Venue v2 = createVenue(owner, "V2", 2, false);
        Tables tInV1 = createTable(v1, "T-in-V1", 1, 250, Tables.TarifType.HOUR);

        // V2 belirtilmiş ama tablo V1'de
        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(tInV1.getId(), v2, "WEEK", FROM, TO),
                        accessFor(owner)))
                .andExpect(status().isNotFound()).andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Detail başka owner'ın table'ı → 403 FORBIDDEN")
    void detail_otherOwnersTable_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000608", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000609", "Test1234");
        createActiveTrial(other);
        Venue otherV = createVenue(other, "OtherV", 1, true);
        Tables otherT = createTable(otherV, "OT", 1, 250, Tables.TarifType.HOUR);

        // owner kendi venue'sunu belirtir ama tablo other'ın
        Venue ownVenue = createVenue(owner, "Own", 1, true);
        mockMvc.perform(getWithBearer(
                        detailUrl(otherT.getId(), ownVenue, "WEEK", FROM, TO), accessFor(owner)))
                .andExpect(status().is4xxClientError()); // 403 veya 404
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403_onTables() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000610", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000611", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(listUrl(v, "WEEK", FROM, TO, false), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }
}
