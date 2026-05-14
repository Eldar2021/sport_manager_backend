package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/refresh";

    @Test
    @DisplayName("happy path: geçerli refresh → 200 + yeni token pair + user YOK")
    void validRefresh_returnsNewPair_noUser() throws Exception {
        User u = createOwner("owner@x.com", "+996700000030", "Test1234");
        String refresh = refreshFor(u, true);

        MvcResult r = mockMvc.perform(postJson(URL, Map.of("refreshToken", refresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andReturn();

        JsonNode body = body(r);
        String newRefresh = body.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh); // rotation
        User reloaded = userRepository.findByEmail("owner@x.com").orElseThrow();
        assertThat(reloaded.getRefreshToken()).isEqualTo(newRefresh);
    }

    @Test
    @DisplayName("rotation: eski refresh ikinci kullanım → 401 SESSION_EXPIRED")
    void rotatedRefresh_cannotBeReused() throws Exception {
        User u = createOwner("owner@x.com", "+996700000031", "Test1234");
        String oldRefresh = refreshFor(u, true);

        mockMvc.perform(postJson(URL, Map.of("refreshToken", oldRefresh)))
                .andExpect(status().isOk());

        MvcResult r = mockMvc.perform(postJson(URL, Map.of("refreshToken", oldRefresh)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    @Test
    @DisplayName("boş refresh token → 422 validation")
    void blankRefresh_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of("refreshToken", "")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("missing refresh field → 422")
    void missingField_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("invalid JWT → 401 SESSION_EXPIRED envelope")
    void invalidRefresh_returns401() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, Map.of("refreshToken", "not.a.jwt")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    @Test
    @DisplayName("Access token /refresh'a gönderildi → 401 (sadece refresh kabul edilmeli)")
    void accessTokenSentToRefresh_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000032", "Test1234");
        String access = accessFor(u);

        MvcResult r = mockMvc.perform(postJson(URL, Map.of("refreshToken", access)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    @Test
    @DisplayName("refresh DB'de yok (logout sonrası) → 401 SESSION_EXPIRED")
    void refreshNotInDb_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000033", "Test1234");
        String refresh = refreshFor(u, true);
        // logout simulation:
        u.setRefreshToken(null);
        userRepository.saveAndFlush(u);

        MvcResult r = mockMvc.perform(postJson(URL, Map.of("refreshToken", refresh)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "SESSION_EXPIRED");
    }

    @Test
    @DisplayName("refresh endpoint Bearer header'sız çağrılır → 200 (public endpoint)")
    void refresh_isPublic_noAuthHeaderRequired() throws Exception {
        User u = createOwner("owner@x.com", "+996700000034", "Test1234");
        String refresh = refreshFor(u, true);

        mockMvc.perform(postJson(URL, Map.of("refreshToken", refresh)))
                .andExpect(status().isOk());
    }
}
