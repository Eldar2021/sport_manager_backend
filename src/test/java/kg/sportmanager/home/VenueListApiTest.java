package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VenueListApiTest extends HomeTestSupport {

    private static final String URL = "/api/v1/venue/list";

    @Test
    @DisplayName("OWNER kendi mekanlarını listeler; number ASC, sonra createdAt ASC")
    void owner_listsOwnVenues_orderedByNumberAsc() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000200", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "Botanika", 2, false);
        createVenue(owner, "Merkez", 1, true);
        createVenue(owner, "Doğu", 3, false);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get(0).get("number").asInt()).isEqualTo(1);
        assertThat(body.get(1).get("number").asInt()).isEqualTo(2);
        assertThat(body.get(2).get("number").asInt()).isEqualTo(3);
        assertThat(body.get(0).get("selected").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Hiç mekan yoksa → 200 []")
    void noVenues_returnsEmptyArray() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000201", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(r).isArray()).isTrue();
        assertThat(body(r).size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Soft-deleted mekanlar listeye dahil edilmez")
    void softDeletedVenues_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000202", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "Aktif", 1, true);
        createDeletedVenue(owner, "Silinmiş", 2);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(r).size()).isEqualTo(1);
        assertThat(body(r).get(0).get("name").asText()).isEqualTo("Aktif");
    }

    @Test
    @DisplayName("MANAGER, bağlı olduğu OWNER'ın mekanlarını görür")
    void manager_seesOwnerVenues() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000203", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000204", "Test1234", owner);
        createVenue(owner, "Owner-A", 1, true);
        createVenue(owner, "Owner-B", 2, false);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(mgr)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(r).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("tableCount alanı doğru hesaplanır (soft-deleted table'lar hariç)")
    void tableCount_excludesSoftDeletedTables() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000205", "Test1234");
        createActiveTrial(owner);
        var venue = createVenue(owner, "V", 1, true);
        createTable(venue, "T1", 1, 100, kg.sportmanager.entity.Tables.TarifType.HOUR);
        createTable(venue, "T2", 2, 100, kg.sportmanager.entity.Tables.TarifType.HOUR);
        createDeletedTable(venue, 3);

        MvcResult r = mockMvc.perform(withBearer(get(URL), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(r).get(0).get("tableCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED (401 sadece expired)")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }
}
