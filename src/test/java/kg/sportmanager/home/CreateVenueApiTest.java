package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
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

class CreateVenueApiTest extends HomeTestSupport {

    private static final String URL = "/api/v1/venue/create";

    private Map<String, Object> payload(String name, int number, String address) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("number", number);
        m.put("address", address);
        return m;
    }

    @Test
    @DisplayName("OWNER ilk mekan oluşturur → 201, selected=true, tableCount=0")
    void firstVenue_isAutoSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000230", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, payload("First", 1, "addr")), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selected").value(true))
                .andExpect(jsonPath("$.tableCount").value(0))
                .andReturn();
        assertThat(body(r).get("id").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("OWNER ikinci mekan oluşturur → 201, selected=false")
    void secondVenue_notSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000231", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "First", 1, true);

        mockMvc.perform(withBearer(
                        postJson(URL, payload("Second", 2, "addr")), accessFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selected").value(false));
    }

    @Test
    @DisplayName("Aynı number ile çakışma → 409 VENUE_NUMBER_TAKEN")
    void duplicateNumber_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000232", "Test1234");
        createActiveTrial(owner);
        createVenue(owner, "First", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, payload("AnotherFirst", 1, "addr")), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NUMBER_TAKEN");
    }

    @Test
    @DisplayName("Soft-deleted venue'nın number'ını yeniden kullanmak OK → 201")
    void softDeletedNumber_canBeReused() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000233", "Test1234");
        createActiveTrial(owner);
        createDeletedVenue(owner, "Old-One", 1);

        mockMvc.perform(withBearer(
                        postJson(URL, payload("NewOne", 1, "addr")), accessFor(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Başka owner aynı number'ı kullanabilir → 201 (number sadece owner içinde unique)")
    void differentOwner_sameNumber_ok() throws Exception {
        User a = createOwner("a@x.com", "+996700000234", "Test1234");
        createActiveTrial(a);
        User b = createOwner("b@x.com", "+996700000235", "Test1234");
        createActiveTrial(b);
        createVenue(a, "A1", 1, true);

        mockMvc.perform(withBearer(
                        postJson(URL, payload("B1", 1, "addr")), accessFor(b)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("MANAGER create venue → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000236", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000237", "Test1234", owner);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, payload("X", 1, "addr")), accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED")
    void expiredSubscription_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000238", "Test1234");
        Instant past = Instant.now().minus(20, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, payload("X", 1, "addr")), accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Validation: boş name → 422")
    void emptyName_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000239", "Test1234");
        createActiveTrial(owner);
        mockMvc.perform(withBearer(
                        postJson(URL, payload("", 1, "addr")), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: number<1 → 422")
    void invalidNumber_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000240", "Test1234");
        createActiveTrial(owner);
        mockMvc.perform(withBearer(
                        postJson(URL, payload("X", 0, "addr")), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: 100+ karakter name → 422")
    void longName_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000241", "Test1234");
        createActiveTrial(owner);
        String longName = "x".repeat(101);
        mockMvc.perform(withBearer(
                        postJson(URL, payload(longName, 1, "addr")), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("address null geçilebilir → 201")
    void nullAddress_isOk() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000242", "Test1234");
        createActiveTrial(owner);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "V");
        body.put("number", 1);
        body.put("address", null);
        mockMvc.perform(withBearer(postJson(URL, body), accessFor(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, payload("X", 1, "addr")))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }
}
