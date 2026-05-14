package kg.sportmanager.subscription;

import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PricingApiTest extends SubscriptionTestSupport {

    private static final String URL = "/api/v1/subscription/pricing";

    @Test
    @DisplayName("OWNER + 3 masa → pricePerTable=200, tableCount=3, monthlyAmount=600")
    void threeTables_correctMonthlyAmount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003100", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 3);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerTable").value(200))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.tableCount").value(3))
                .andExpect(jsonPath("$.monthlyAmount").value(600))
                .andExpect(jsonPath("$.minDurationMonths").value(1))
                .andExpect(jsonPath("$.maxDurationMonths").value(12))
                .andExpect(jsonPath("$.gracePeriodDays").value(5))
                .andExpect(jsonPath("$.freeTrialDays").value(14))
                .andExpect(jsonPath("$.expiryWarningDays").value(3));
    }

    @Test
    @DisplayName("OWNER + 0 masa → tableCount=0, monthlyAmount=0")
    void zeroTables_zeroAmount() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003101", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(0))
                .andExpect(jsonPath("$.monthlyAmount").value(0));
    }

    @Test
    @DisplayName("Soft-deleted masa pricing'e dahil değil (sub fiyatı korunur)")
    void softDeletedTable_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003102", "Test1234");
        createActiveTrial(owner);
        venueWithTables(owner, 2);
        // Bir masayı soft-delete et
        var t = tableRepository.findAll().get(0);
        t.setDeletedAt(java.time.Instant.now());
        tableRepository.saveAndFlush(t);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(1))
                .andExpect(jsonPath("$.monthlyAmount").value(200));
    }

    @Test
    @DisplayName("Soft-deleted venue içindeki masalar da dahil değil")
    void tablesInDeletedVenue_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003103", "Test1234");
        createActiveTrial(owner);
        var v = venueWithTables(owner, 3);
        // Tüm venue'yi soft-delete et
        v.setDeletedAt(java.time.Instant.now());
        venueRepository.saveAndFlush(v);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(0));
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700003104", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700003105", "Test1234", owner);

        mockMvc.perform(getJsonWithBearer(URL, accessFor(mgr)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auth yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL))
                .andExpect(status().isBadRequest());
    }
}
