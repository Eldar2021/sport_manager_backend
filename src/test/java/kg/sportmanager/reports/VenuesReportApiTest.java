package kg.sportmanager.reports;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VenuesReportApiTest extends ReportsTestSupport {

    private static final String URL = "/api/v1/reports/venues";

    @Test
    @DisplayName("OWNER kendi mekanlarını picker formatında alır — number ASC")
    void owner_listsOwnVenues() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000500", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "Botanika", 2, false);
        createVenue(owner, "Merkez", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = body(r);
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).get("number").asInt()).isEqualTo(1);
        assertThat(body.get(1).get("number").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Hiç venue yoksa → 200 []")
    void noVenues_returnsEmpty() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000501", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Soft-deleted venue listeye dahil edilmez")
    void softDeletedExcluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000502", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "Aktif", 1, true);
        createDeletedVenue(owner, "Silindi", 2);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000503", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000504", "Test1234", owner);

        mockMvc.perform(getWithBearer(URL, accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Başka owner'ın venue'ları görünmez")
    void crossOwner_isolated() throws Exception {
        User a = createOwner("a@x.com", "+996700000505", "Test1234");
        createActiveTrial(a);
        User b = createOwner("b@x.com", "+996700000506", "Test1234");
        createActiveTrial(b);
        createVenue(a, "A", 1, true);
        createVenue(b, "B", 1, true);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(a)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(r).size()).isEqualTo(1);
        assertThat(body(r).get(0).get("name").asText()).isEqualTo("A");
    }
}
