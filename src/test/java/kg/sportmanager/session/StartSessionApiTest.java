package kg.sportmanager.session;

import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StartSessionApiTest extends SessionTestSupport {

    private static final String URL = "/api/v1/session/start";

    private Map<String, Object> payload(String tableId) {
        Map<String, Object> m = new HashMap<>();
        m.put("tableId", tableId);
        return m;
    }

    private Map<String, Object> payload(String tableId, String customerName) {
        Map<String, Object> m = new HashMap<>();
        m.put("tableId", tableId);
        m.put("customerName", customerName);
        return m;
    }

    @Test
    @DisplayName("OWNER yeni session başlatır → 201, status=ACTIVE, snapshot fields dolu")
    void owner_startSession_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000400", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tableId").value(t.getId().toString()))
                .andExpect(jsonPath("$.managerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.tarifAmountSnapshot").value(250))
                .andExpect(jsonPath("$.tarifTypeSnapshot").value("HOUR"))
                .andExpect(jsonPath("$.totalPausedSeconds").value(0))
                .andExpect(jsonPath("$.pausedAt").isEmpty())
                .andReturn();
        assertThat(body(r).get("startedAt").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("MANAGER kendi owner'ının masasında session başlatır → 201, managerId=manager.id")
    void manager_startSession_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000401", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000402", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(mgr)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.managerId").value(mgr.getId().toString()));
    }

    @Test
    @DisplayName("Snapshot kuralı: session başladıktan sonra masa fiyatı değişse mevcut session etkilenmez")
    void snapshotRule_priceChangeAfterStart_doesNotAffectSession() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000403", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(owner)))
                .andExpect(status().isCreated())
                .andReturn();
        int snapshotAmount = body(r).get("tarifAmountSnapshot").asInt();

        // Masa fiyatını değiştir
        t.setTarifAmount(999);
        tableRepository.saveAndFlush(t);

        // Session'ı DB'den oku — snapshot hâlâ 250 olmalı
        var session = sessionRepository.findById(java.util.UUID.fromString(body(r).get("id").asText())).orElseThrow();
        assertThat(session.getTarifAmountSnapshot()).isEqualTo(250);
        assertThat(snapshotAmount).isEqualTo(250);
    }

    @Test
    @DisplayName("Aynı masada aktif session varken yeni start → 409 TABLE_HAS_ACTIVE_SESSION")
    void existingActiveSession_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000404", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);
        createActiveSession(t, owner);

        MvcResult r = mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_HAS_ACTIVE_SESSION");
    }

    @Test
    @DisplayName("Bilinmeyen tableId → 404 TABLE_NOT_FOUND")
    void unknownTable_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000405", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(postWithBearer(URL,
                        payload(java.util.UUID.randomUUID().toString()), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Soft-deleted table → 404 TABLE_NOT_FOUND")
    void softDeletedTable_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000406", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createDeletedTable(v, 1);

        mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Başka owner'ın masasında session başlatma → 403 FORBIDDEN")
    void otherOwnersTable_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000407", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000408", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);
        Tables otherTable = createTable(otherVenue, "X1", 1, 100, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(postWithBearer(URL,
                        payload(otherTable.getId().toString()), accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED")
    void expiredSubscription_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000409", "Test1234");
        Instant past = Instant.now().minus(20, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner).status(Subscription.Status.EXPIRED).source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS)).endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS)).build());
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(postWithBearer(URL, payload(t.getId().toString()), accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Validation: boş tableId → 422")
    void emptyTableId_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000410", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(postWithBearer(URL, payload(""), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: bozuk UUID → 422 VALIDATION_ERROR")
    void invalidUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000411", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(postWithBearer(URL, payload("not-a-uuid"), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("customerName: trim edilir, DB'ye yazılır, response'ta döner; pause body'sindeki yeni değer YOK SAYILIR (immutability)")
    void customerName_isTrimmedAndImmutableAfterStart() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000412", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        MvcResult started = mockMvc.perform(postWithBearer(URL,
                        payload(t.getId().toString(), "  Asan  "), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Asan"))
                .andReturn();
        java.util.UUID sessionId = java.util.UUID.fromString(body(started).get("id").asText());

        // DB snapshot
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getCustomerName())
                .isEqualTo("Asan");

        // Pause body'sinde manipülasyon denemesi — Jackson DTO'da alan tanımsız, ignore edilir.
        // Response'taki customerName start'tan korunmalı.
        Map<String, Object> tamper = new HashMap<>();
        tamper.put("customerName", "Manipulated");
        mockMvc.perform(postWithBearer("/api/v1/session/" + sessionId + "/pause", tamper, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Asan"));

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getCustomerName())
                .as("pause body'sinden gelen yeni isim DB'ye yazılmamalı (snapshot/immutable)")
                .isEqualTo("Asan");
    }

    @Test
    @DisplayName("customerName: boş/whitespace → NULL (hata değil), gönderilmemiş ile aynı davranış")
    void customerName_blankBecomesNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000413", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t1 = createTable(v, "T1", 1, 250, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 250, Tables.TarifType.HOUR);

        // Whitespace-only
        MvcResult r1 = mockMvc.perform(postWithBearer(URL,
                        payload(t1.getId().toString(), "   "), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").isEmpty())
                .andReturn();
        assertThat(sessionRepository.findById(java.util.UUID.fromString(body(r1).get("id").asText()))
                .orElseThrow().getCustomerName()).isNull();

        // Hiç gönderilmemiş
        MvcResult r2 = mockMvc.perform(postWithBearer(URL,
                        payload(t2.getId().toString()), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").isEmpty())
                .andReturn();
        assertThat(sessionRepository.findById(java.util.UUID.fromString(body(r2).get("id").asText()))
                .orElseThrow().getCustomerName()).isNull();
    }

    @Test
    @DisplayName("customerName: trim sonrası >80 char → 422 INVALID_CUSTOMER_NAME")
    void customerName_tooLong_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000414", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 250, Tables.TarifType.HOUR);

        String tooLong = "A".repeat(81);
        MvcResult r = mockMvc.perform(postWithBearer(URL,
                        payload(t.getId().toString(), tooLong), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_CUSTOMER_NAME");

        // Sınırda — tam 80 char (trim sonrası) kabul edilmeli
        String exact = "B".repeat(80);
        mockMvc.perform(postWithBearer(URL,
                        payload(t.getId().toString(), "  " + exact + "  "), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value(exact));
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(URL)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                payload(java.util.UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }
}
