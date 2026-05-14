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

class CancelSessionApiTest extends SessionTestSupport {

    private String url(java.util.UUID id) { return "/api/v1/session/" + id + "/cancel"; }

    private Map<String, Object> reason(String r) {
        Map<String, Object> m = new HashMap<>();
        m.put("reason", r);
        return m;
    }

    @Test
    @DisplayName("OWNER cancel → 200 CANCELLED, totalAmount=null, cancelReason set")
    void owner_cancel_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000460", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()),
                        reason("yanlış başladım"), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("yanlış başladım"))
                .andExpect(jsonPath("$.totalAmount").isEmpty())
                .andExpect(jsonPath("$.durationSeconds").isEmpty())
                .andReturn();
        assertThat(body(r).get("endedAt").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("MANAGER ilk 60sn içinde cancel edebilir → 200")
    void manager_within60s_canCancel() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000461", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000462", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, mgr);

        mockMvc.perform(postWithBearer(url(s.getId()), reason("ops"), accessFor(mgr)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER 60sn sonrası cancel → 422 CANCEL_WINDOW_EXPIRED")
    void manager_after60s_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000463", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000464", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, mgr);
        // 90 saniye geriye al
        s.setStartedAt(Instant.now().minusSeconds(90));
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), reason("ops"), accessFor(mgr)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(body(r), "CANCEL_WINDOW_EXPIRED");

        // Session değişmemiş olmalı (hâlâ aktif)
        Session reloaded = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(Session.SessionStatus.ACTIVE);
    }

    @Test
    @DisplayName("OWNER 60sn sonra da cancel edebilir → 200")
    void owner_after60s_canCancel() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000465", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setStartedAt(Instant.now().minusSeconds(600));
        sessionRepository.saveAndFlush(s);

        mockMvc.perform(postWithBearer(url(s.getId()), reason("müşteri kaçtı"), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("Validation: boş reason → 422 VALIDATION_ERROR")
    void emptyReason_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000466", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postWithBearer(url(s.getId()), reason(""), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: 200+ karakter reason → 422")
    void longReason_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000467", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postWithBearer(url(s.getId()), reason("x".repeat(201)), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Zaten COMPLETED → 409 SESSION_ALREADY_COMPLETED")
    void cancelCompleted_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000468", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setActive(false);
        s.setStatus(Session.SessionStatus.COMPLETED);
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), reason("late"), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_ALREADY_COMPLETED");
    }

    @Test
    @DisplayName("Zaten CANCELLED → 409 SESSION_ALREADY_CANCELLED (doğru mesaj)")
    void cancelAlreadyCancelled_returnsCorrectCode() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000469", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setActive(false);
        s.setStatus(Session.SessionStatus.CANCELLED);
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postWithBearer(url(s.getId()), reason("late"), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        // CANCELLED bir session için doğru mesaj SESSION_ALREADY_CANCELLED olmalı
        // (mevcut kod SESSION_ALREADY_COMPLETED dönüyor — yanlış)
        assertErrorEnvelope(body(r), "SESSION_ALREADY_CANCELLED");
    }

    @Test
    @DisplayName("Bilinmeyen sessionId → 404")
    void unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000470", "Test1234");
        createActiveTrial(owner);
        mockMvc.perform(postWithBearer(url(java.util.UUID.randomUUID()), reason("x"), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Cancel sonrası masa boş → yeni session başlatılabilir")
    void afterCancel_canStartNewSession() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000471", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postWithBearer(url(s.getId()), reason("ops"), accessFor(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(postWithBearer("/api/v1/session/start",
                        Map.of("tableId", t.getId().toString()), accessFor(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Cancel edilen session veritabanında kalır (audit) — durationSeconds/totalAmount null")
    void cancelledSession_persistedForAudit() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000472", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postWithBearer(url(s.getId()), reason("audit-trail"), accessFor(owner)))
                .andExpect(status().isOk());

        Session reloaded = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Session.SessionStatus.CANCELLED);
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.getCancelReason()).isEqualTo("audit-trail");
        assertThat(reloaded.getDurationSeconds()).isNull();
        assertThat(reloaded.getTotalAmount()).isNull();
        assertThat(reloaded.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("Başka owner'ın session'ı → 403 FORBIDDEN")
    void otherOwnersSession_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000473", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000474", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);
        Tables otherTable = createTable(otherVenue, "X", 1, 100, Tables.TarifType.HOUR);
        Session otherSession = createActiveSession(otherTable, other);

        mockMvc.perform(postWithBearer(url(otherSession.getId()), reason("hijack"), accessFor(owner)))
                .andExpect(status().isForbidden());
    }
}
