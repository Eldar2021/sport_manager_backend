package kg.sportmanager.session;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinishSessionApiTest extends SessionTestSupport {

    private String url(java.util.UUID id) { return "/api/v1/session/" + id + "/finish"; }

    private Map<String, Object> discount(int pct) {
        Map<String, Object> m = new HashMap<>();
        m.put("discountPercent", pct);
        return m;
    }

    /** Test için backdate edilmiş bir session yarat — finish'te gerçekçi süre çıksın. */
    private Session createBackdatedSession(Venue v, User who, long startedSecondsAgo) {
        Tables t = createTable(v, "T-" + System.nanoTime(), (int) (System.nanoTime() & 0xFFFF), 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, who);
        s.setStartedAt(Instant.now().minusSeconds(startedSecondsAgo));
        sessionRepository.saveAndFlush(s);
        return s;
    }

    @Test
    @DisplayName("Aktif session finish (discountPercent yok) → 200 COMPLETED, totalAmount=subtotal")
    void finish_happyPath_noDiscount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000440", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        // 1 saat geriye start → ~250 KGS subtotal (HOUR tarifi)
        Session s = createBackdatedSession(v, owner, 3600);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andReturn();

        int subtotal = body(r).get("subtotal").asInt();
        int total = body(r).get("totalAmount").asInt();
        assertThat(subtotal).isGreaterThanOrEqualTo(249).isLessThanOrEqualTo(251);
        assertThat(total).isEqualTo(subtotal);
        assertThat(body(r).get("discountPercent").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("Discount %10 → totalAmount = subtotal - round(subtotal*0.1)")
    void finish_withDiscount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000441", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Session s = createBackdatedSession(v, owner, 3600);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), discount(10), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();

        int subtotal = body(r).get("subtotal").asInt();
        int total = body(r).get("totalAmount").asInt();
        int expectedDiscount = Math.round(subtotal * 10f / 100f);
        assertThat(total).isEqualTo(subtotal - expectedDiscount);
        assertThat(body(r).get("discountPercent").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("Discount 100 → totalAmount = 0")
    void finish_fullDiscount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000442", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Session s = createBackdatedSession(v, owner, 3600);

        mockMvc.perform(postWithBearer(url(s.getId()), discount(100), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    @DisplayName("Discount<0 veya >100 → 422 INVALID_DISCOUNT veya validation")
    void invalidDiscount_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000443", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Session s = createBackdatedSession(v, owner, 3600);

        mockMvc.perform(postWithBearer(url(s.getId()), discount(150), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("PAUSED session finish → auto-resume + 200 (mevcut pause süresi totalPausedSeconds'a eklenir)")
    void finish_pausedSession_autoResumes() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000444", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Session s = createBackdatedSession(v, owner, 3600); // 1 saat önce başladı
        // 10 dakika önce pause edildi
        s.setPaused(true);
        s.setPausedAt(Instant.now().minusSeconds(600));
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();

        // billable seconds ≈ 3600 - 600 = 3000 sec = 50 min ≈ 50/60 * 250 = ~208
        int subtotal = body(r).get("subtotal").asInt();
        assertThat(subtotal).isLessThan(250)
                .as("Pause süresi billable'dan düşürülmeli (subtotal < tam saatlik fiyat)");
    }

    @Test
    @DisplayName("Tarif MINUTE: 600 saniye → tarif * 10 dakika")
    void finish_minuteTarif() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000445", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 5, Tables.TarifType.MINUTE);
        Session s = createActiveSession(t, owner);
        s.setStartedAt(Instant.now().minusSeconds(600)); // 10 dakika
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        int subtotal = body(r).get("subtotal").asInt();
        assertThat(subtotal).isGreaterThanOrEqualTo(49).isLessThanOrEqualTo(51);
    }

    @Test
    @DisplayName("Zaten COMPLETED → 409 SESSION_ALREADY_COMPLETED")
    void finishCompleted_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000446", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setActive(false);
        s.setStatus(Session.SessionStatus.COMPLETED);
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_ALREADY_COMPLETED");
    }

    @Test
    @DisplayName("Bilinmeyen sessionId → 404")
    void unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000447", "Test1234");
        createActiveTrial(owner);
        mockMvc.perform(postWithBearer(url(java.util.UUID.randomUUID()), Map.of(), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Başka owner'ın session'ını finish → 403 FORBIDDEN")
    void otherOwnersSession_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000448", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000449", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);
        Tables otherTable = createTable(otherVenue, "X", 1, 100, Tables.TarifType.HOUR);
        Session otherSession = createActiveSession(otherTable, other);

        mockMvc.perform(postWithBearer(url(otherSession.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Empty body (request null) → 200 (discount=0 default)")
    void emptyBody_isOk() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000450", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Session s = createBackdatedSession(v, owner, 3600);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(url(s.getId()))
                        .header("Authorization", "Bearer " + accessFor(owner))
                        .contentType("application/json"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Finish sonrası masa boş → yeni session başlatılabilir")
    void afterFinish_canStartNewSession() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000451", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setStartedAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(s);

        mockMvc.perform(postWithBearer(url(s.getId()), Map.of(), accessFor(owner)))
                .andExpect(status().isOk());

        // Yeni session başlatma
        mockMvc.perform(postWithBearer("/api/v1/session/start",
                        Map.of("tableId", t.getId().toString()), accessFor(owner)))
                .andExpect(status().isCreated());
    }
}
