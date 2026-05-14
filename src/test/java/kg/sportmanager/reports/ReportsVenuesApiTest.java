package kg.sportmanager.reports;

import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsVenuesApiTest extends ReportsTestSupport {

    private static final String URL = "/api/v1/reports/venues";

    @Test
    @DisplayName("OWNER → 200, kendi venue'lerini number ASC sırada görür")
    void owner_returnsOwnVenues() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001000", "Test1234");
        createActiveTrial(owner);
        Venue v2 = createVenue(owner, "B", 2, false);
        Venue v1 = createVenue(owner, "A", 1, true);
        createVenue(owner, "C", 3, false);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].id").value(v1.getId().toString()))
                .andExpect(jsonPath("$[1].number").value(2))
                .andExpect(jsonPath("$[1].id").value(v2.getId().toString()))
                .andExpect(jsonPath("$[2].number").value(3));
    }

    @Test
    @DisplayName("Hiç venue yok → 200 + boş array")
    void noVenues_returnsEmptyArray() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001001", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Soft-deleted venue listede yok")
    void deletedVenue_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001002", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "Active", 1, true);
        createDeletedVenue(owner, "Deleted", 2);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Active"));
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN (Reports OWNER-only)")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001003", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700001004", "Test1234", owner);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Başka owner'ın venue'lerini görmez (multi-tenant izolasyon)")
    void otherOwnersVenue_notVisible() throws Exception {
        User owner1 = createOwner("o1@x.com", "+996700001005", "Test1234");
        createActiveTrial(owner1);
        createVenue(owner1, "MyVenue", 1, true);

        User owner2 = createOwner("o2@x.com", "+996700001006", "Test1234");
        createActiveTrial(owner2);
        createVenue(owner2, "OtherVenue", 1, true);

        mockMvc.perform(getWithBearer(URL, accessFor(owner1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("MyVenue"));
    }
}
