package kg.sportmanager.auth;

import kg.sportmanager.entity.InviteCode;
import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.repository.PaymentRepository;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeleteAccountApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/account";

    @Autowired private VenueRepository venueRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private PaymentRepository paymentRepository;

    // ─── OWNER cascade ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER delete → 200 + cascade: venues/tables/sessions/payments/subs/invites/managers/user hepsi gider")
    void owner_cascadeDelete_wipesEverything() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000100", "Test1234");
        Subscription sub = createActiveTrial(owner);
        Venue v = createVenueFor(owner, "Branch", 1);
        Tables t = createTableFor(v, "T1", 1);
        User mgr = createManager("mgr@x.com", "+996700000101", "Test1234", owner);
        createCompletedSessionFor(t, mgr);
        Payment p = createPaymentFor(sub);
        createInvite(owner, "INVITE-DEL-1", LocalDateTime.now().plusDays(7), false);

        UUID ownerId = owner.getId();
        UUID mgrId = mgr.getId();
        UUID venueId = v.getId();
        UUID tableId = t.getId();
        UUID paymentId = p.getId();

        String access = accessFor(owner);

        mockMvc.perform(withBearer(delete(URL), access))
                .andExpect(status().isOk());

        // Hepsi gitti
        assertThat(userRepository.findById(ownerId)).isEmpty();
        assertThat(userRepository.findById(mgrId)).isEmpty();
        assertThat(venueRepository.findById(venueId)).isEmpty();
        assertThat(tableRepository.findById(tableId)).isEmpty();
        assertThat(sessionRepository.count()).isEqualTo(0);
        assertThat(paymentRepository.findById(paymentId)).isEmpty();
        assertThat(subscriptionRepository.findByOwner(owner)).isEmpty();
        assertThat(inviteCodeRepository.findByCodeAndUsedFalse("INVITE-DEL-1")).isEmpty();
    }

    @Test
    @DisplayName("OWNER silindi → aynı email ile yeni OWNER register edilebilir")
    void afterOwnerDelete_sameEmailCanRegister() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000102", "Test1234");
        createActiveTrial(owner);
        String access = accessFor(owner);

        mockMvc.perform(withBearer(delete(URL), access)).andExpect(status().isOk());

        // Aynı email + phone ile yeni register
        mockMvc.perform(postJson("/api/v1/auth/register",
                        registerOwnerPayload("New", "owner@x.com", "+996700000102", "Test1234")))
                .andExpect(status().isOk());

        User reRegistered = userRepository.findByEmail("owner@x.com").orElseThrow();
        assertThat(reRegistered.getId()).isNotEqualTo(owner.getId()); // fresh UUID
        assertThat(reRegistered.getRole()).isEqualTo(User.Role.OWNER);
    }

    @Test
    @DisplayName("OWNER silindi → silinen access token ile sonraki istek başarısız (user yok)")
    void afterOwnerDelete_accessTokenInvalidates() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000103", "Test1234");
        createActiveTrial(owner);
        String access = accessFor(owner);

        mockMvc.perform(withBearer(delete(URL), access)).andExpect(status().isOk());

        // Filter user'ı bulamaz → SecurityContext set edilmez → 400 UNAUTHORIZED
        mockMvc.perform(withBearer(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/profile"),
                        access))
                .andExpect(status().isBadRequest());
    }

    // ─── MANAGER soft-delete ────────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER delete → 200 + soft-delete (deletedAt set, PII anonymized, sessions preserved)")
    void manager_softDelete_preservesSessionsForReports() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000110", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000111", "Test1234", owner);
        Venue v = createVenueFor(owner, "Branch", 1);
        Tables t = createTableFor(v, "T1", 1);
        Session sess = createCompletedSessionFor(t, mgr);
        UUID mgrId = mgr.getId();
        UUID sessId = sess.getId();

        String access = accessFor(mgr);

        mockMvc.perform(withBearer(delete(URL), access)).andExpect(status().isOk());

        // Manager row hâlâ DB'de
        User reloadedMgr = userRepository.findById(mgrId).orElseThrow();
        assertThat(reloadedMgr.getDeletedAt()).isNotNull();
        assertThat(reloadedMgr.getEmail()).isNull();
        assertThat(reloadedMgr.getPhone()).isNull();
        assertThat(reloadedMgr.getRefreshToken()).isNull();
        assertThat(reloadedMgr.isLocked()).isTrue();
        // Display fields KORUNUR (reports için)
        assertThat(reloadedMgr.getName()).isEqualTo("Test Manager");
        assertThat(reloadedMgr.getHandle()).isNotBlank();
        assertThat(reloadedMgr.getOwner()).isNotNull();
        assertThat(reloadedMgr.getOwner().getId()).isEqualTo(owner.getId());

        // Session hâlâ DB'de ve manager_id geçerli
        Session reloadedSess = sessionRepository.findById(sessId).orElseThrow();
        assertThat(reloadedSess.getManager().getId()).isEqualTo(mgrId);
    }

    @Test
    @DisplayName("Silinen MANAGER aynı email ile tekrar register olabilir (fresh user, new UUID)")
    void afterManagerDelete_sameEmailCanReRegister() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000112", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000113", "Test1234", owner);
        createInvite(owner, "INVITE-REREG", LocalDateTime.now().plusDays(7), false);
        UUID oldMgrId = mgr.getId();

        // Manager kendini siler
        mockMvc.perform(withBearer(delete(URL), accessFor(mgr))).andExpect(status().isOk());

        // Aynı email + phone ile yeni register (yeni invite ile)
        mockMvc.perform(postJson("/api/v1/auth/register",
                        registerManagerPayload("Test Manager", "mgr@x.com", "+996700000113",
                                "Test1234", "INVITE-REREG")))
                .andExpect(status().isOk());

        // Yeni manager (yeni UUID) + eski hâlâ soft-deleted
        User newMgr = userRepository.findByEmail("mgr@x.com").orElseThrow();
        assertThat(newMgr.getId()).isNotEqualTo(oldMgrId);
        assertThat(newMgr.getDeletedAt()).isNull();

        User oldMgr = userRepository.findById(oldMgrId).orElseThrow();
        assertThat(oldMgr.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Silinen MANAGER login deneyebilir → 400 INVALID_CREDENTIALS (email=null)")
    void afterManagerDelete_loginFails() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000114", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000115", "Test1234", owner);

        mockMvc.perform(withBearer(delete(URL), accessFor(mgr))).andExpect(status().isOk());

        MvcResult r = mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "mgr@x.com", "password", "Test1234")))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("OWNER manager listesi siliyor (cascade hard-delete) — owner alive iken manager hard-delete edilirse FK temizliği")
    void ownerDelete_alsoHardDeletesAllManagers() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000116", "Test1234");
        createActiveTrial(owner);
        User m1 = createManager("m1@x.com", "+996700000117", "T", owner);
        User m2 = createManager("m2@x.com", "+996700000118", "T", owner);
        UUID m1Id = m1.getId();
        UUID m2Id = m2.getId();

        mockMvc.perform(withBearer(delete(URL), accessFor(owner))).andExpect(status().isOk());

        assertThat(userRepository.findById(m1Id)).isEmpty();
        assertThat(userRepository.findById(m2Id)).isEmpty();
    }

    // ─── Auth / error path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(delete(URL))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Bozuk token → 400 INVALID_TOKEN")
    void invalidToken_returns400() throws Exception {
        MvcResult r = mockMvc.perform(withBearer(delete(URL), "not.a.jwt"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_TOKEN");
    }

    @Test
    @DisplayName("Expired access token → 401 SESSION_EXPIRED (yalnız bu durum 401)")
    void expiredAccess_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000119", "Test1234");
        String expired = io.jsonwebtoken.Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject(u.getId().toString())
                .claim("role", u.getRole().name())
                .claim("type", "access")
                .setIssuedAt(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                .setExpiration(new java.util.Date(System.currentTimeMillis() - 1_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-key-please-do-not-use-in-production-1234567890"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        MvcResult r = mockMvc.perform(withBearer(delete(URL), expired))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private Venue createVenueFor(User owner, String name, int number) {
        return venueRepository.saveAndFlush(Venue.builder()
                .owner(owner).name(name).number(number).selected(number == 1).build());
    }

    private Tables createTableFor(Venue venue, String name, int number) {
        return tableRepository.saveAndFlush(Tables.builder()
                .venue(venue).name(name).number(number)
                .tarifAmount(200).currency(Tables.Currency.KGS).tarifType(Tables.TarifType.HOUR)
                .build());
    }

    private Session createCompletedSessionFor(Tables table, User manager) {
        return sessionRepository.saveAndFlush(Session.builder()
                .table(table).manager(manager)
                .isActive(false).isPaused(false)
                .startedAt(Instant.now().minusSeconds(3600))
                .endedAt(Instant.now())
                .totalPausedSeconds(0)
                .tarifAmountSnapshot(200).tarifTypeSnapshot(Tables.TarifType.HOUR)
                .status(Session.SessionStatus.COMPLETED)
                .durationSeconds(3600).totalAmount(200L)
                .build());
    }

    private Payment createPaymentFor(Subscription sub) {
        return paymentRepository.saveAndFlush(Payment.builder()
                .subscription(sub).amount(2000L).currency(Tables.Currency.KGS)
                .months(1).tableCountSnapshot(1).pricePerTableSnapshot(200)
                .status(Payment.Status.PAID).provider(Payment.Provider.MOCK)
                .build());
    }
}
