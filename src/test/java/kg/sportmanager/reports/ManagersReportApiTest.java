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

class ManagersReportApiTest extends ReportsTestSupport {

    private final Instant FROM = Instant.parse("2026-05-07T00:00:00Z");
    private final Instant TO   = Instant.parse("2026-05-14T00:00:00Z");

    private String listUrl(Venue v, Instant from, Instant to) {
        return "/api/v1/reports/managers?venueId=" + v.getId()
                + "&period=WEEK&from=" + from + "&to=" + to;
    }

    private String detailUrl(java.util.UUID managerId, Venue v, Instant from, Instant to) {
        return "/api/v1/reports/managers/" + managerId
                + "?venueId=" + v.getId()
                + "&period=WEEK&from=" + from + "&to=" + to;
    }

    // ─── /reports/managers ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Manager listesi: revenue DESC, sadece o venue'da session açanlar")
    void managers_sortedByRevenue() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000620", "Test1234");
        createActiveTrial(owner);
        User mgr1 = createManager("mgr1@x.com", "+996700000621", "Test1234", owner);
        User mgr2 = createManager("mgr2@x.com", "+996700000622", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant in = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t, mgr1, in, in.plusSeconds(3600), 100L);
        completedSession(t, mgr2, in, in.plusSeconds(3600), 300L);
        completedSession(t, mgr2, in.plusSeconds(7200), in.plusSeconds(10800), 200L);
        cancelledSession(t, mgr1, in.plusSeconds(11000), "ops");

        MvcResult r = mockMvc.perform(getWithBearer(listUrl(v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).get("managerId").asText()).isEqualTo(mgr2.getId().toString());
        assertThat(body.get(0).get("revenue").asLong()).isEqualTo(500);
        assertThat(body.get(0).get("sessions").asLong()).isEqualTo(2);
        assertThat(body.get(1).get("managerId").asText()).isEqualTo(mgr1.getId().toString());
        assertThat(body.get(1).get("revenue").asLong()).isEqualTo(100);
        assertThat(body.get(1).get("cancelCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("KRİTİK: OWNER kendi session'larını yönetirse, /managers listesinde managerId=owner.id olarak görünmeli")
    void owner_asSelfManager_inList() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000623", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant in = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t, owner, in, in.plusSeconds(3600), 250L);

        MvcResult r = mockMvc.perform(getWithBearer(listUrl(v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("managerId").asText()).isEqualTo(owner.getId().toString());
        assertThat(body.get(0).get("revenue").asLong()).isEqualTo(250);
    }

    @Test
    @DisplayName("Boş data → 200 []")
    void noData_emptyList() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000624", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(listUrl(v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(0);
    }

    // ─── /reports/managers/{id} ─────────────────────────────────────────────────

    @Test
    @DisplayName("Manager detail: summary + sessionLog döner")
    void detail_returnsSummaryAndLog() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000625", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000626", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant in = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t, mgr, in, in.plusSeconds(3600), 250L);
        cancelledSession(t, mgr, in.plusSeconds(7200), "ops");

        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(mgr.getId(), v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.get("summary").get("revenue").asLong()).isEqualTo(250);
        assertThat(body.get("summary").get("sessions").asLong()).isEqualTo(1);
        assertThat(body.get("summary").get("cancelCount").asLong()).isEqualTo(1);
        assertThat(body.get("sessionLog").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("KRİTİK BUG: OWNER kendi managerId'siyle detail çağırdığında → 200 (404 değil)")
    void owner_asSelfManager_detail_returns200() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000627", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        Instant in = FROM.plus(1, ChronoUnit.DAYS);
        completedSession(t, owner, in, in.plusSeconds(3600), 250L);

        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(owner.getId(), v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = body(r);
        assertThat(body.get("summary").get("managerId").asText()).isEqualTo(owner.getId().toString());
        assertThat(body.get("summary").get("revenue").asLong()).isEqualTo(250);
        assertThat(body.get("sessionLog").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bilinmeyen managerId → 404 MANAGER_NOT_FOUND")
    void unknownManagerId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000628", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(java.util.UUID.randomUUID(), v, FROM, TO), accessFor(owner)))
                .andExpect(status().isNotFound()).andReturn();
        assertErrorEnvelope(body(r), "MANAGER_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın manager'ı → 404 MANAGER_NOT_FOUND (sızdırma yok)")
    void otherOwnersManager_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000629", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000630", "Test1234");
        createActiveTrial(other);
        User otherMgr = createManager("om@x.com", "+996700000631", "Test1234", other);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(detailUrl(otherMgr.getId(), v, FROM, TO), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER (caller) → 403 FORBIDDEN (sadece OWNER)")
    void caller_isManager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000632", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000633", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(getWithBearer(detailUrl(mgr.getId(), v, FROM, TO), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sessionLog: max 40 satır, startedAt DESC")
    void sessionLog_max40_descOrder() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000634", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000635", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        // 50 session oluştur
        for (int i = 0; i < 50; i++) {
            Instant s = FROM.plus(i, ChronoUnit.HOURS);
            completedSession(t, mgr, s, s.plusSeconds(1800), 50L);
        }

        MvcResult r = mockMvc.perform(getWithBearer(
                        detailUrl(mgr.getId(), v, FROM, TO), accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode log = body(r).get("sessionLog");
        assertThat(log.size()).isEqualTo(40);
        // İlk eleman en yeni
        Instant first = Instant.parse(log.get(0).get("startedAt").asText());
        Instant second = Instant.parse(log.get(1).get("startedAt").asText());
        assertThat(first).isAfter(second);
    }
}
