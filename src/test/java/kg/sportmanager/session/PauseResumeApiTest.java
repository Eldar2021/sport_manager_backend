package kg.sportmanager.session;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PauseResumeApiTest extends SessionTestSupport {

    private String pauseUrl(java.util.UUID id) { return "/api/v1/session/" + id + "/pause"; }
    private String resumeUrl(java.util.UUID id) { return "/api/v1/session/" + id + "/resume"; }

    // ─── PAUSE ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Aktif session pause → 200, status=PAUSED, pausedAt set")
    void pause_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000420", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postEmptyWithBearer(pauseUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.pausedAt").isNotEmpty())
                .andExpect(jsonPath("$.totalPausedSeconds").value(0));
    }

    @Test
    @DisplayName("KRİTİK: Zaten pause'da olan session'a tekrar pause → 409 SESSION_ALREADY_PAUSED (pausedAt OVERWRITE EDİLMEMELİ)")
    void doublePause_returns409_andDoesNotOverwritePausedAt() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000421", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        // İlk pause
        mockMvc.perform(postEmptyWithBearer(pauseUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isOk());
        Session afterFirstPause = sessionRepository.findById(s.getId()).orElseThrow();
        Instant firstPausedAt = afterFirstPause.getPausedAt();
        assertThat(firstPausedAt).isNotNull();

        // İkinci pause — reddedilmeli (status conflict)
        Thread.sleep(50);
        MvcResult r = mockMvc.perform(postEmptyWithBearer(pauseUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_ALREADY_PAUSED");

        // pausedAt değişmemeli — ilk pause anı korunmalı
        Session afterSecondAttempt = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(afterSecondAttempt.getPausedAt())
                .as("İkinci pause girişimi pausedAt'i üzerine yazmamalı (pause süresi kaybedilmemeli)")
                .isEqualTo(firstPausedAt);
    }

    @Test
    @DisplayName("COMPLETED session pause → 409 SESSION_ALREADY_COMPLETED")
    void pauseCompleted_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000422", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setActive(false);
        s.setStatus(Session.SessionStatus.COMPLETED);
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postEmptyWithBearer(pauseUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_ALREADY_COMPLETED");
    }

    @Test
    @DisplayName("Bilinmeyen sessionId pause → 404 SESSION_NOT_FOUND")
    void pauseUnknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000423", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(postEmptyWithBearer(
                        pauseUrl(java.util.UUID.randomUUID()), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın session'ını pause → 403 FORBIDDEN")
    void pauseOtherOwnersSession_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000424", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000425", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);
        Tables otherTable = createTable(otherVenue, "X1", 1, 100, Tables.TarifType.HOUR);
        Session otherSession = createActiveSession(otherTable, other);

        MvcResult r = mockMvc.perform(postEmptyWithBearer(pauseUrl(otherSession.getId()), accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("MANAGER, kendi owner'ının session'ını pause edebilir → 200")
    void manager_canPause() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000426", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000427", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        mockMvc.perform(postEmptyWithBearer(pauseUrl(s.getId()), accessFor(mgr)))
                .andExpect(status().isOk());
    }

    // ─── RESUME ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PAUSED session resume → 200, status=ACTIVE, totalPausedSeconds artar, pausedAt=null")
    void resume_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000428", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        // Manually pause with pausedAt in the past so totalPausedSeconds > 0
        s.setPaused(true);
        s.setPausedAt(Instant.now().minusSeconds(120));
        sessionRepository.saveAndFlush(s);

        MvcResult r = mockMvc.perform(postEmptyWithBearer(resumeUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pausedAt").isEmpty())
                .andReturn();
        int totalPausedSeconds = body(r).get("totalPausedSeconds").asInt();
        assertThat(totalPausedSeconds).isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("ACTIVE (not paused) session resume → 409 SESSION_NOT_PAUSED")
    void resumeNonPaused_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000429", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        MvcResult r = mockMvc.perform(postEmptyWithBearer(resumeUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_NOT_PAUSED");
    }

    @Test
    @DisplayName("COMPLETED session resume → 409 SESSION_ALREADY_COMPLETED")
    void resumeCompleted_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000430", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);
        s.setActive(false);
        s.setStatus(Session.SessionStatus.COMPLETED);
        sessionRepository.saveAndFlush(s);

        mockMvc.perform(postEmptyWithBearer(resumeUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Pause→Resume→Pause→Resume birden çok kere → totalPausedSeconds accumulator artar")
    void multipleCycles_accumulatePausedSeconds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000431", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        Session s = createActiveSession(t, owner);

        // Cycle 1: pause with backdated pausedAt → resume
        s.setPaused(true);
        s.setPausedAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(s);
        mockMvc.perform(postEmptyWithBearer(resumeUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        Session afterFirstResume = sessionRepository.findById(s.getId()).orElseThrow();
        int firstAccum = afterFirstResume.getTotalPausedSeconds();
        assertThat(firstAccum).isGreaterThanOrEqualTo(60);

        // Cycle 2: yine backdated pause → resume
        afterFirstResume.setPaused(true);
        afterFirstResume.setPausedAt(Instant.now().minusSeconds(30));
        sessionRepository.saveAndFlush(afterFirstResume);
        mockMvc.perform(postEmptyWithBearer(resumeUrl(s.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        Session afterSecondResume = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(afterSecondResume.getTotalPausedSeconds())
                .as("İkinci pause-resume cycle totalPausedSeconds'a eklenmeli")
                .isGreaterThanOrEqualTo(firstAccum + 30);
    }

    @Test
    @DisplayName("Bilinmeyen sessionId resume → 404")
    void resumeUnknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000432", "Test1234");
        createActiveTrial(owner);
        mockMvc.perform(postEmptyWithBearer(resumeUrl(java.util.UUID.randomUUID()), accessFor(owner)))
                .andExpect(status().isNotFound());
    }
}
