package kg.sportmanager.home;

import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VenueTablesListApiTest extends HomeTestSupport {

    private String url(String venueId) {
        return "/api/v1/venue/" + venueId + "/tables";
    }

    @Test
    @DisplayName("OWNER: venue + 3 masa → 200, number ASC sıralı, tüm alanlar dolu")
    void owner_returnsTables_sortedByNumberAsc() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004000", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        // Out of order yarat, number ASC olmalı response'ta
        Tables t3 = createTable(v, "T3", 3, 300, Tables.TarifType.DAY);
        Tables t1 = createTable(v, "T1", 1, 100, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 200, Tables.TarifType.MINUTE);

        MvcResult r = mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(t1.getId().toString()))
                .andExpect(jsonPath("$[0].venueId").value(v.getId().toString()))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].name").value("T1"))
                .andExpect(jsonPath("$[0].tarifAmount").value(100))
                .andExpect(jsonPath("$[0].currency").value("KGS"))
                .andExpect(jsonPath("$[0].tarifType").value("HOUR"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].id").value(t2.getId().toString()))
                .andExpect(jsonPath("$[1].tarifType").value("MINUTE"))
                .andExpect(jsonPath("$[2].id").value(t3.getId().toString()))
                .andExpect(jsonPath("$[2].tarifType").value("DAY"))
                .andReturn();

        // session field response'ta YOKSA OK; varsa fail
        String json = r.getResponse().getContentAsString();
        if (json.contains("\"session\"")) {
            throw new AssertionError("Lightweight list 'session' alanı dönmemeli: " + json);
        }
    }

    @Test
    @DisplayName("Boş mekan → 200 + boş array")
    void emptyVenue_returnsEmptyArray() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004001", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Soft-deleted masa listede yok")
    void softDeletedTable_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004002", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "Active", 1, 100, Tables.TarifType.HOUR);
        createDeletedTable(v, 2);

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Active"));
    }

    @Test
    @DisplayName("MANAGER: bağlı olduğu owner'ın venue'sünü görür")
    void manager_canSeeOwnersVenueTables() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004003", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700004004", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(mgr)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404 VENUE_NOT_FOUND")
    void unknownVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004005", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(get(url(java.util.UUID.randomUUID().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Soft-deleted venue → 404 VENUE_NOT_FOUND")
    void deletedVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004006", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        v.setDeletedAt(Instant.now());
        venueRepository.saveAndFlush(v);

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Başka owner'ın venue'si → 404 (multi-tenant info-leak yok)")
    void otherOwnersVenue_returns404() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700004007", "Test1234");
        createActiveTrial(o1);
        User o2 = createOwner("o2@x.com", "+996700004008", "Test1234");
        createActiveTrial(o2);
        Venue othersVenue = createVenue(o2, "OthersV", 1, true);
        createTable(othersVenue, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(get(url(othersVenue.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(o1)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER, başka owner'ın venue'sünü çağırırsa → 404")
    void manager_otherOwnersVenue_returns404() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700004009", "Test1234");
        createActiveTrial(o1);
        User mgr = createManager("mgr@x.com", "+996700004010", "Test1234", o1);
        User o2 = createOwner("o2@x.com", "+996700004011", "Test1234");
        createActiveTrial(o2);
        Venue othersVenue = createVenue(o2, "OthersV", 1, true);

        mockMvc.perform(get(url(othersVenue.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(mgr)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bozuk venueId UUID → 422 VALIDATION_ERROR")
    void invalidUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004012", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(get(url("not-a-uuid"))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        mockMvc.perform(get(url(java.util.UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Bozuk Bearer token → 400 INVALID_TOKEN")
    void invalidBearer_returns400() throws Exception {
        mockMvc.perform(get(url(java.util.UUID.randomUUID().toString()))
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Subscription gate'siz: EXPIRED owner endpoint'i çekebilir (read endpoint)")
    void expiredOwner_canList() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004013", "Test1234");
        Instant past = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(kg.sportmanager.entity.Subscription.builder()
                .owner(owner)
                .status(kg.sportmanager.entity.Subscription.Status.EXPIRED)
                .source(kg.sportmanager.entity.Subscription.Source.TRIAL)
                .startDate(past.minus(14, java.time.temporal.ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, java.time.temporal.ChronoUnit.DAYS))
                .build());
        Venue v = createVenue(owner, "V", 1, true);
        createTable(v, "T", 1, 100, Tables.TarifType.HOUR);

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Nullable alanlar: name=null + description=null doğru serialize edilir")
    void nullableFields_serializedAsNull() throws Exception {
        User owner = createOwner("owner@x.com", "+996700004014", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = tableRepository.saveAndFlush(Tables.builder()
                .venue(v).number(1).tarifAmount(150)
                .currency(Tables.Currency.USD).tarifType(Tables.TarifType.HOUR)
                .name(null).description(null)
                .build());

        mockMvc.perform(get(url(v.getId().toString()))
                        .header("Authorization", "Bearer " + accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(t.getId().toString()))
                .andExpect(jsonPath("$[0].name").isEmpty())                   // JsonInclude.ALWAYS → field var, null
                .andExpect(jsonPath("$[0].description").isEmpty())
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }
}
