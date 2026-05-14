package kg.sportmanager.managers;

import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagersListApiTest extends ManagersTestSupport {

    private static final String URL = "/api/v1/managers";

    @Test
    @DisplayName("OWNER, kendi manager listesini görür — boş takım → 200 + []")
    void owner_emptyTeam_returnsEmptyArray() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002000", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("OWNER, manager fields döndürür: id, name, username, lastSeenAt")
    void owner_returnsManagerFields() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002001", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("aibek@x.com", "+996700002002", "Test1234", owner);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mgr.getId().toString()))
                .andExpect(jsonPath("$[0].name").value("Test Manager"))
                .andExpect(jsonPath("$[0].username").isNotEmpty())
                .andExpect(jsonPath("$[0].lastSeenAt").isEmpty());           // null at first
    }

    @Test
    @DisplayName("Sıralama: lastSeenAt DESC NULLS LAST, name ASC")
    void sortedByLastSeenDesc_nullsLast_thenName() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002003", "Test1234");
        createActiveTrial(owner);

        User m1 = createManager("a@x.com", "+996700002004", "Test1234", owner);
        m1.setName("Aibek");
        m1.setLastSeenAt(Instant.now().minus(1, ChronoUnit.HOURS));          // newer
        userRepository.saveAndFlush(m1);

        User m2 = createManager("b@x.com", "+996700002005", "Test1234", owner);
        m2.setName("Beka");
        m2.setLastSeenAt(Instant.now().minus(3, ChronoUnit.HOURS));          // older
        userRepository.saveAndFlush(m2);

        User m3 = createManager("c@x.com", "+996700002006", "Test1234", owner);
        m3.setName("Daniyar");
        m3.setLastSeenAt(null);                                              // null → last
        userRepository.saveAndFlush(m3);

        User m4 = createManager("d@x.com", "+996700002007", "Test1234", owner);
        m4.setName("Chyngyz");
        m4.setLastSeenAt(null);                                              // null → also last, by name
        userRepository.saveAndFlush(m4);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("Aibek"))             // most recent
                .andExpect(jsonPath("$[1].name").value("Beka"))
                .andExpect(jsonPath("$[2].name").value("Chyngyz"))           // null+name ASC
                .andExpect(jsonPath("$[3].name").value("Daniyar"));
    }

    @Test
    @DisplayName("Soft-deleted manager listede yok")
    void softDeletedManager_excluded() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002008", "Test1234");
        createActiveTrial(owner);
        createManager("active@x.com", "+996700002009", "Test1234", owner);
        User deleted = createManager("del@x.com", "+996700002010", "Test1234", owner);
        deleted.setDeletedAt(Instant.now());
        userRepository.saveAndFlush(deleted);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(
                        userRepository.findByEmail("active@x.com").orElseThrow().getId().toString()));
    }

    @Test
    @DisplayName("Multi-tenant: başka owner'ın managerları görünmez")
    void otherOwnersManagers_notVisible() throws Exception {
        User o1 = createOwner("o1@x.com", "+996700002011", "Test1234");
        createActiveTrial(o1);
        createManager("m1@x.com", "+996700002012", "Test1234", o1);

        User o2 = createOwner("o2@x.com", "+996700002013", "Test1234");
        createActiveTrial(o2);
        createManager("m2@x.com", "+996700002014", "Test1234", o2);
        createManager("m3@x.com", "+996700002015", "Test1234", o2);

        mockMvc.perform(getWithBearer(URL, accessFor(o1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("MANAGER rolü /managers çağırırsa → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700002016", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700002017", "Test1234", owner);

        MvcResult r = mockMvc.perform(getWithBearer(URL, accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("List endpoint subscription gate'e takılmaz (read endpoint)")
    void list_notAffectedBySubscriptionGate() throws Exception {
        // EXPIRED subscription owner — list yine 200 dönmeli (read endpoint)
        User owner = createOwner("owner@x.com", "+996700002018", "Test1234");
        Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(kg.sportmanager.entity.Subscription.builder()
                .owner(owner)
                .status(kg.sportmanager.entity.Subscription.Status.EXPIRED)
                .source(kg.sportmanager.entity.Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());
        createManager("mgr@x.com", "+996700002019", "Test1234", owner);

        mockMvc.perform(getWithBearer(URL, accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
