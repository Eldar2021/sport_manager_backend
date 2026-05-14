package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SelectedVenueApiTest extends HomeTestSupport {

    private static final String GET_URL = "/api/v1/venue/selected";
    private static final String PATCH_URL = "/api/v1/venue/selected";

    @Test
    @DisplayName("GET /selected → seçili mekan + tabloları döner")
    void get_returnsSelectedVenueAndTables() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000210", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T1", 1, 250, kg.sportmanager.entity.Tables.TarifType.HOUR);

        MvcResult r = mockMvc.perform(withBearer(get(GET_URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.get("venue").get("name").asText()).isEqualTo("V");
        assertThat(body.get("tables").size()).isEqualTo(1);
        assertThat(body.get("tables").get(0).get("name").asText()).isEqualTo("T1");
    }

    @Test
    @DisplayName("GET /selected → seçili yoksa en eski auto-select edilir + DB'de selected=true olur")
    void get_autoSelectsOldestWhenNoneSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000211", "Test1234");
        createActiveTrial(owner);
        Venue oldest = createVenue(owner, "Old", 1, false);
        createVenue(owner, "New", 2, false);

        MvcResult r = mockMvc.perform(withBearer(get(GET_URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(r).get("venue").get("name").asText()).isEqualTo("Old");
        // DB'de de selected=true olmalı
        Venue reloaded = venueRepository.findById(oldest.getId()).orElseThrow();
        assertThat(reloaded.isSelected()).isTrue();
    }

    @Test
    @DisplayName("GET /selected → hiç mekan yok → 404 VENUE_NOT_FOUND")
    void get_returnsNotFoundWhenNoVenues() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000212", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(get(GET_URL), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("GET /selected → MANAGER, owner'ın seçili mekanını görür")
    void get_managerSeesOwnerSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000213", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000214", "Test1234", owner);
        createVenue(owner, "V1", 1, true);

        mockMvc.perform(withBearer(get(GET_URL), accessFor(mgr)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /selected → mekan değiştirme: eski selected=false, yeni selected=true")
    void patch_switchesSelectedFlag() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000215", "Test1234");
        createActiveTrial(owner);
        Venue a = createVenue(owner, "A", 1, true);
        Venue b = createVenue(owner, "B", 2, false);

        mockMvc.perform(withBearer(patch(PATCH_URL), accessFor(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("venueId", b.getId().toString()))))
                .andExpect(status().isOk());

        assertThat(venueRepository.findById(a.getId()).orElseThrow().isSelected()).isFalse();
        assertThat(venueRepository.findById(b.getId()).orElseThrow().isSelected()).isTrue();
    }

    @Test
    @DisplayName("PATCH /selected → bilinmeyen venueId → 404")
    void patch_unknownVenueId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000216", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(patch(PATCH_URL), accessFor(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("venueId", java.util.UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("PATCH /selected → başka owner'ın venue'su → 404 VENUE_NOT_FOUND (sızdırma yok)")
    void patch_otherOwnersVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000217", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000218", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "OtherVenue", 1, true);

        MvcResult r = mockMvc.perform(withBearer(patch(PATCH_URL), accessFor(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("venueId", otherVenue.getId().toString()))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("PATCH /selected → soft-deleted venue → 404")
    void patch_softDeletedVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000219", "Test1234");
        createActiveTrial(owner);
        Venue deleted = createDeletedVenue(owner, "Silinmiş", 1);

        mockMvc.perform(withBearer(patch(PATCH_URL), accessFor(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("venueId", deleted.getId().toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /selected → invalid UUID → 422")
    void patch_invalidUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000220", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(patch(PATCH_URL), accessFor(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("venueId", "not-a-uuid"))))
                .andExpect(status().isUnprocessableEntity());
    }
}
