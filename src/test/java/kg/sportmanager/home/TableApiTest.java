package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TableApiTest extends HomeTestSupport {

    private Map<String, Object> createPayload(String venueId, String name, int number,
                                              int tarifAmount, String currency, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueId", venueId);
        m.put("name", name);
        m.put("number", number);
        m.put("description", null);
        m.put("tarifAmount", tarifAmount);
        m.put("currency", currency);
        m.put("tarifType", type);
        return m;
    }

    private Map<String, Object> updatePayload(String name, int number, int tarifAmount,
                                              String currency, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("number", number);
        m.put("description", null);
        m.put("tarifAmount", tarifAmount);
        m.put("currency", currency);
        m.put("tarifType", type);
        return m;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            String url, Object body) throws Exception {
        return put(url)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    // ─── CREATE ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER create table happy path → 201")
    void createTable_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000300", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T1", 1, 250, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("T1"))
                .andExpect(jsonPath("$.tarifAmount").value(250))
                .andExpect(jsonPath("$.session").doesNotExist())
                .andReturn();
        assertThat(body(r).get("id").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("Aynı venue içinde aynı number → 409 TABLE_NUMBER_TAKEN")
    void createTable_duplicateNumber_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000301", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T1", 1, 100, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T2", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_NUMBER_TAKEN");
    }

    @Test
    @DisplayName("Soft-deleted table'ın number'ı yeniden kullanılabilir → 201")
    void createTable_softDeletedNumber_isReusable() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000302", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createDeletedTable(v, 1);

        mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T-new", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Farklı venue'larda aynı number kullanılabilir → 201 (number sadece venue içinde unique)")
    void createTable_differentVenues_sameNumber_ok() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000303", "Test1234");
        createActiveTrial(owner);
        Venue v1 = createVenue(owner, "V1", 1, true);
        Venue v2 = createVenue(owner, "V2", 2, false);
        createTable(v1, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v2.getId().toString(), "T", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("venueId başka owner'a aitse → 404 VENUE_NOT_FOUND")
    void createTable_otherOwnersVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000304", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000305", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(otherVenue.getId().toString(), "T", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("MANAGER create table → 403 FORBIDDEN")
    void createTable_manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000306", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000307", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T", 1, 100, "KGS", "HOUR")),
                        accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Validation: tarifAmount<1 → 422")
    void createTable_invalidTarifAmount_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000308", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T", 1, 0, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: tarifAmount>1M → 422")
    void createTable_tooLargeTarifAmount_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000309", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T", 1, 1_000_001, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: invalid currency enum → 400/422")
    void createTable_invalidCurrency_returns4xx() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000310", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson("/api/v1/table/create",
                                createPayload(v.getId().toString(), "T", 1, 100, "EURO", "HOUR")),
                        accessFor(owner)))
                .andReturn();
        // Jackson enum parse fail → 400 BAD_REQUEST veya VALIDATION_ERROR → 422
        int s = r.getResponse().getStatus();
        assertThat(s == 400 || s == 422).as("status %s should be 4xx for invalid enum", s).isTrue();
    }

    // ─── UPDATE ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER update table happy path → 200, alanlar değişir")
    void updateTable_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000311", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "Eski", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + t.getId(),
                                updatePayload("Yeni", 5, 200, "USD", "MINUTE")),
                        accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yeni"))
                .andExpect(jsonPath("$.tarifAmount").value(200))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.tarifType").value("MINUTE"));
    }

    @Test
    @DisplayName("Aynı number ile self-update → 200")
    void updateTable_sameNumber_isOk() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000312", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + t.getId(),
                                updatePayload("T-rename", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Number çakışması → 409 TABLE_NUMBER_TAKEN")
    void updateTable_conflictingNumber_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000313", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T1", 1, 100, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + t2.getId(),
                                updatePayload("T2-update", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Bilinmeyen tableId → 404 TABLE_NOT_FOUND")
    void updateTable_unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000314", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + java.util.UUID.randomUUID(),
                                updatePayload("T", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın table'ı → 403 FORBIDDEN (mevcut kod davranışı)")
    void updateTable_otherOwnersTable() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000315", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000316", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "X", 1, true);
        Tables otherTable = createTable(otherVenue, "X1", 1, 100, Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + otherTable.getId(),
                                updatePayload("Hijack", 1, 100, "KGS", "HOUR")),
                        accessFor(owner)))
                .andReturn();
        // 403 veya 404 — ikisi de meşru (sızdırma kapatma). Şu an servis FORBIDDEN dönüyor.
        int s = r.getResponse().getStatus();
        assertThat(s == 403 || s == 404).as("status %s should be 403 or 404", s).isTrue();
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void updateTable_manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000317", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000318", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/table/" + t.getId(),
                                updatePayload("Hack", 1, 100, "KGS", "HOUR")),
                        accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    // ─── DELETE ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER table siler → 200, deletedAt set")
    void deleteTable_happyPath() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000319", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(delete("/api/v1/table/" + t.getId()), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        assertThat(tableRepository.findById(t.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Aktif session varsa → 409 TABLE_HAS_ACTIVE_SESSION, deletedAt set EDİLMEZ")
    void deleteTable_activeSession_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000320", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);
        createActiveSession(t, owner);

        mockMvc.perform(withBearer(delete("/api/v1/table/" + t.getId()), accessFor(owner)))
                .andExpect(status().isConflict());

        assertThat(tableRepository.findById(t.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Bilinmeyen tableId → 404")
    void deleteTable_unknownId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000321", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(delete("/api/v1/table/" + java.util.UUID.randomUUID()),
                        accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403")
    void deleteTable_manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000322", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000323", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(delete("/api/v1/table/" + t.getId()), accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Soft-deleted table'ı tekrar silmek → 404")
    void deleteTable_alreadyDeleted_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000324", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createDeletedTable(v, 1);

        mockMvc.perform(withBearer(delete("/api/v1/table/" + t.getId()), accessFor(owner)))
                .andExpect(status().isNotFound());
    }
}
