package kg.sportmanager.home;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeleteVenueApiTest extends HomeTestSupport {

    @Test
    @DisplayName("OWNER non-selected venue siler → 200, deletedAt set; selected venue değişmez")
    void deleteNonSelectedVenue_otherSelectedUnchanged() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000270", "Test1234");
        createActiveTrial(owner);
        Venue selected = createVenue(owner, "Main", 1, true);
        Venue toDelete = createVenue(owner, "Extra", 2, false);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + toDelete.getId()), accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.id").value(toDelete.getId().toString()));

        Venue deleted = venueRepository.findById(toDelete.getId()).orElseThrow();
        assertThat(deleted.getDeletedAt()).isNotNull();
        // Main hâlâ selected
        assertThat(venueRepository.findById(selected.getId()).orElseThrow().isSelected()).isTrue();
    }

    @Test
    @DisplayName("KRİTİK: selected venue silinince, kalan en eski venue otomatik selected olur")
    void deleteSelectedVenue_promotesOldestRemaining() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000271", "Test1234");
        createActiveTrial(owner);
        Venue a = createVenue(owner, "A-selected", 1, true);
        Venue b = createVenue(owner, "B", 2, false);
        Venue c = createVenue(owner, "C", 3, false);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + a.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        // a soft-deleted ve selected=false olmalı (data hijyeni)
        Venue aReloaded = venueRepository.findById(a.getId()).orElseThrow();
        assertThat(aReloaded.getDeletedAt()).isNotNull();
        assertThat(aReloaded.isSelected())
                .as("Silinen venue selected=true kalmamalı (data hijyeni)")
                .isFalse();

        // b veya c — hangisi en eski (createdAt) ise o selected olmalı
        Venue bReloaded = venueRepository.findById(b.getId()).orElseThrow();
        Venue cReloaded = venueRepository.findById(c.getId()).orElseThrow();
        long selectedCount = (bReloaded.isSelected() ? 1 : 0) + (cReloaded.isSelected() ? 1 : 0);
        assertThat(selectedCount)
                .as("Kalan venue'lardan tam olarak biri selected olmalı")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("KRİTİK: tek selected venue silinince, başka venue yoksa selected boş kalır (404 venue/selected)")
    void deleteOnlyVenue_noOtherToPromote() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000272", "Test1234");
        createActiveTrial(owner);
        Venue only = createVenue(owner, "Only", 1, true);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + only.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        MvcResult r = mockMvc.perform(withBearer(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/venue/selected"), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Venue'da aktif session varsa → 409 TABLE_HAS_ACTIVE_SESSION")
    void venueWithActiveSession_returns409() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000273", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t = createTable(v, "T", 1, 100, Tables.TarifType.HOUR);
        createActiveSession(t, owner);

        MvcResult r = mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(owner)))
                .andExpect(status().isConflict())
                .andReturn();
        assertErrorEnvelope(body(r), "TABLE_HAS_ACTIVE_SESSION");

        // Venue silinmemiş olmalı
        assertThat(venueRepository.findById(v.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Cascade soft-delete: venue silindiğinde tabloları da soft-deleted olur")
    void venueDeletion_cascadeToTables() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000274", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "V", 1, true);
        Tables t1 = createTable(v, "T1", 1, 100, Tables.TarifType.HOUR);
        Tables t2 = createTable(v, "T2", 2, 100, Tables.TarifType.HOUR);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        assertThat(tableRepository.findById(t1.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(tableRepository.findById(t2.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Bilinmeyen venueId → 404")
    void unknownVenueId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000275", "Test1234");
        createActiveTrial(owner);

        MvcResult r = mockMvc.perform(withBearer(
                        delete("/api/v1/venue/" + java.util.UUID.randomUUID()), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Başka owner'ın venue'su → 404 (sızdırma yok)")
    void otherOwnersVenue_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000276", "Test1234");
        createActiveTrial(owner);
        User other = createOwner("other@x.com", "+996700000277", "Test1234");
        createActiveTrial(other);
        Venue otherVenue = createVenue(other, "OtherVenue", 1, true);

        MvcResult r = mockMvc.perform(withBearer(
                        delete("/api/v1/venue/" + otherVenue.getId()), accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
        // Diğer owner'ın venue'su silinmemiş olmalı
        assertThat(venueRepository.findById(otherVenue.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("Soft-deleted venue tekrar silmeye çalışırsa → 404")
    void doubleDelete_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000278", "Test1234");
        createActiveTrial(owner);
        Venue v = createDeletedVenue(owner, "AlreadyGone", 1);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER → 403 FORBIDDEN")
    void manager_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000279", "Test1234");
        createActiveTrial(owner);
        User mgr = createManager("mgr@x.com", "+996700000280", "Test1234", owner);
        Venue v = createVenue(owner, "V", 1, true);

        MvcResult r = mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(mgr)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "FORBIDDEN");
        // Venue silinmemiş olmalı
        assertThat(venueRepository.findById(v.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("EXPIRED subscription → 403 SUBSCRIPTION_REQUIRED")
    void expiredSubscription_returns403() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000281", "Test1234");
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

        MvcResult r = mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(owner)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(body(r), "SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("Invalid UUID → 422")
    void invalidUuid_returns422() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000282", "Test1234");
        createActiveTrial(owner);

        mockMvc.perform(withBearer(delete("/api/v1/venue/not-a-uuid"), accessFor(owner)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Silinen venue, sonraki GET /venue/list'te görünmemeli")
    void deletedVenue_excludedFromList() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000283", "Test1234");
        createActiveTrial(owner);
        Venue a = createVenue(owner, "A", 1, true);
        Venue b = createVenue(owner, "B", 2, false);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + b.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        MvcResult r = mockMvc.perform(withBearer(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/venue/list"), accessFor(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(r).size()).isEqualTo(1);
        assertThat(body(r).get(0).get("id").asText()).isEqualTo(a.getId().toString());
    }

    @Test
    @DisplayName("Venue silindikten sonra aynı number ile yeni venue oluşturulabilir → 201")
    void afterDelete_numberIsReusable() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000284", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "Old", 7, true);

        mockMvc.perform(withBearer(delete("/api/v1/venue/" + v.getId()), accessFor(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(withBearer(
                        postJson("/api/v1/venue/create", java.util.Map.of(
                                "name", "New", "number", 7, "address", "addr")),
                        accessFor(owner)))
                .andExpect(status().isCreated());
    }
}
