package kg.sportmanager.reports;

import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edge case: owner tek venue'sünü sildikten sonra Reports endpoint'lerini çağırırsa
 * ne olmalı? Mobile'ın bildirdiği bug: 500 INTERNAL_SERVER_ERROR.
 * Beklenen: 4xx (graceful error) — asla 500 değil.
 */
class ReportsNoVenueEdgeCaseTest extends ReportsTestSupport {

    @Test
    @DisplayName("Tek venue silindi, mobile venueId GÖNDERMEDEN /revenue-series → 400 (500 değil)")
    void revenueSeries_missingVenueIdAfterDelete_returns400NotInternalError() throws Exception {
        // Bug raporu birebir senaryosu:
        // 1) Owner'ın tek venue'sü vardı
        // 2) Onu sildi → venue listesi boş
        // 3) Mobile reports ekranını açtı, venueId olmadan /revenue-series çağırdı
        User owner = createOwner("owner@x.com", "+996700001800", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "OnlyVenue", 1, true);
        v.setDeletedAt(Instant.now());
        venueRepository.saveAndFlush(v);

        String url = "/api/v1/reports/revenue-series"
                + "?period=YEAR"
                + "&from=2025-12-31T18:00:00.000Z"
                + "&to=2026-05-14T18:00:00.000Z"
                + "&compare=true";

        MvcResult r = mockMvc.perform(getWithBearer(url, accessFor(owner)))
                .andExpect(status().is4xxClientError())
                .andReturn();

        int status = r.getResponse().getStatus();
        assertThat(status)
                .as("Spec: 4xx beklenir — 500 INTERNAL_SERVER_ERROR olmamalı")
                .isGreaterThanOrEqualTo(400)
                .isLessThan(500);
        // Envelope formatı korunmuş olmalı
        assertThat(body(r).has("code")).isTrue();
        assertThat(body(r).has("message")).isTrue();
    }

    @Test
    @DisplayName("Tek venue silindi, mobile eski venueId ile /revenue-series → 404 VENUE_NOT_FOUND (500 değil)")
    void revenueSeries_deletedVenueId_returns404() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001801", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "OnlyVenue", 1, true);
        String deletedVenueId = v.getId().toString();
        v.setDeletedAt(Instant.now());
        venueRepository.saveAndFlush(v);

        String url = "/api/v1/reports/revenue-series"
                + "?venueId=" + deletedVenueId
                + "&period=YEAR"
                + "&from=2025-12-31T18:00:00.000Z"
                + "&to=2026-05-14T18:00:00.000Z"
                + "&compare=true";

        MvcResult r = mockMvc.perform(getWithBearer(url, accessFor(owner)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(body(r), "VENUE_NOT_FOUND");
    }

    @Test
    @DisplayName("Mobile boş venueId='' ile /revenue-series → 422/400 (500 değil)")
    void revenueSeries_emptyVenueId_returns4xx() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001802", "Test1234");
        createActiveTrial(owner);

        String url = "/api/v1/reports/revenue-series"
                + "?venueId="
                + "&period=YEAR"
                + "&from=2025-12-31T18:00:00.000Z"
                + "&to=2026-05-14T18:00:00.000Z";

        MvcResult r = mockMvc.perform(getWithBearer(url, accessFor(owner)))
                .andExpect(status().is4xxClientError())
                .andReturn();

        int status = r.getResponse().getStatus();
        assertThat(status).isLessThan(500);
    }

    @Test
    @DisplayName("Aynı no-venue durumu /overview için → 4xx, 500 değil")
    void overview_missingVenueIdAfterDelete_returns4xx() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001803", "Test1234");
        createActiveTrial(owner);

        String url = "/api/v1/reports/overview"
                + "?period=MONTH"
                + "&from=2026-05-01T00:00:00.000Z"
                + "&to=2026-05-15T00:00:00.000Z";

        MvcResult r = mockMvc.perform(getWithBearer(url, accessFor(owner)))
                .andExpect(status().is4xxClientError())
                .andReturn();
        assertThat(r.getResponse().getStatus()).isLessThan(500);
    }

    @Test
    @DisplayName("Aynı no-venue durumu /forecast için → 4xx, 500 değil")
    void forecast_missingVenueIdAfterDelete_returns4xx() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001804", "Test1234");
        createActiveTrial(owner);

        String url = "/api/v1/reports/forecast"
                + "?period=YEAR"
                + "&from=2025-12-31T18:00:00.000Z"
                + "&to=2026-05-14T18:00:00.000Z";

        MvcResult r = mockMvc.perform(getWithBearer(url, accessFor(owner)))
                .andExpect(status().is4xxClientError())
                .andReturn();
        assertThat(r.getResponse().getStatus()).isLessThan(500);
    }

    @Test
    @DisplayName("/reports/venues hala 200 + boş array döner (mobile empty-state için bağımlı)")
    void venuesEndpoint_returnsEmptyArrayAfterAllDeleted() throws Exception {
        User owner = createOwner("owner@x.com", "+996700001805", "Test1234");
        createActiveTrial(owner);
        Venue v = createVenue(owner, "OnlyVenue", 1, true);
        v.setDeletedAt(Instant.now());
        venueRepository.saveAndFlush(v);

        mockMvc.perform(getWithBearer("/api/v1/reports/venues", accessFor(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.length()").value(0));
    }
}
