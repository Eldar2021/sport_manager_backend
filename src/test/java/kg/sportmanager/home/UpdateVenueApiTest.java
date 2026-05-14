package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UpdateVenueApiTest extends HomeTestSupport {

    private Map<String, Object> payload(String name, int number, String address) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("number", number);
        m.put("address", address);
        return m;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            String url, Object body) throws Exception {
        return put(url)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    @Test
    @DisplayName("OWNER kendi venue'sunu günceller → 200 + alanlar değişir, selected korunur")
    void owner_updatesOwnVenue_keepsSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000250", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "Eski", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("Yeni", 5, "yeni-addr")),
                        accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yeni"))
                .andExpect(jsonPath("$.number").value(5))
                .andExpect(jsonPath("$.address").value("yeni-addr"))
                .andExpect(jsonPath("$.selected").value(true)) // selected bayrağı dokunulmamalı
                .andReturn();
        assertThat(body(r).get("id").asText()).isEqualTo(v.getId().toString());
    }

    @Test
    @DisplayName("Aynı number ile self-update → 200 (number conflict check kendi kendisini hariç tutar)")
    void selfUpdate_sameNumber_isOk() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000251", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("V-renamed", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Number çakışması (başka venue) → 409 VENUE_NUMBER_TAKEN")
    void conflictingNumber_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000252", "Test1234");
        createActiveTrial(owner);
        Venue a = createVenue(owner, "A", 1, true);
        Venue b = createVenue(owner, "B", 2, false);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + b.getId(), payload("B", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NUMBER_TAKEN");
    }

    @Test
    @DisplayName("Soft-deleted venue'nın number'ı update'te yeniden kullanılabilir → 200")
    void numberFromSoftDeleted_isReusable() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000253", "Test1234");
        createActiveTrial(owner);
        Venue active = createVenue(owner, "A", 5, true);
        createDeletedVenue(owner, "OldA", 1);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + active.getId(), payload("A", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404 VENUE_NOT_FOUND")
    void unknownVenueId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000254", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + java.util.UUID.randomUUID(),
                                payload("X", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın venue'su → 404 (sızdırma yok)")
    void otherOwnersVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000255", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000256", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "OtherVenue", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + otherVenue.getId(), payload("Hijack", 9, null)),
                        accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Soft-deleted venue'yu update etmek → 404 (gizli)")
    void softDeletedVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000257", "Test1234");
        createActiveTrial(owner);
        Venue deleted = createDeletedVenue(owner, "Silindi", 1);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + deleted.getId(), payload("X", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Invalid UUID format → 422 VALIDATION_ERROR")
    void invalidUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000258", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/not-a-uuid", payload("X", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: boş name → 422")
    void emptyName_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000259", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000260", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000261", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("X", 1, null)),
                        accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
    }

    @Test
    @DisplayName("EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED (update gate kontrolü)")
    void expiredSubscription_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000262", "Test1234");
        Instant past = Instant.now().minus(20, ChronoUnit.DAYS);
        subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.EXPIRED)
                .source(Subscription.Source.TRIAL)
                .startDate(past.minus(14, ChronoUnit.DAYS))
                .endDate(past)
                .gracePeriodEndsAt(past.plus(5, ChronoUnit.DAYS))
                .build());
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("X", 1, null)),
                        accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Update venue: selected=true mekan number değişse de selected korunmalı")
    void update_doesNotResetSelected() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000263", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);

        mockMvc.perform(withBearer(
                        putJson("/api/v1/venue/" + v.getId(), payload("V", 9, "addr")),
                        accessFor(owner)))
                .andExpect(status().isOk());

        Venue reloaded = venueRepository.findById(v.getId()).orElseThrow();
        assertThat(reloaded.isSelected()).isTrue();
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Auth header yok → 400 UNAUTHORIZED")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(
                        putJson("/api/v1/venue/" + java.util.UUID.randomUUID(), payload("X", 1, null)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }
}
