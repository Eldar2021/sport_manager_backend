package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LogoutApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/logout";

    @Test
    @DisplayName("happy path: geçerli access ile logout → 200 + refresh token DB'den temizlenir")
    void validToken_logout_clearsRefresh() throws Exception {
        User u = createOwner("owner@x.com", "+996700000040", "Test1234");
        refreshFor(u, true);
        assertThat(userRepository.findByEmail("owner@x.com").orElseThrow().getRefreshToken()).isNotNull();
        String access = accessFor(u);

        mockMvc.perform(withBearer(post(URL), access))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail("owner@x.com").orElseThrow().getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("Authorization header yok → 401 UNAUTHORIZED envelope")
    void noAuthHeader_returns401Envelope() throws Exception {
        MvcResult r = mockMvc.perform(post(URL))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Bozuk token → 401 UNAUTHORIZED envelope")
    void invalidToken_returns401() throws Exception {
        MvcResult r = mockMvc.perform(withBearer(post(URL), "not.a.real.jwt"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Refresh token /logout'a gönderildi → 401 INVALID_TOKEN_TYPE")
    void refreshTokenSentToLogout_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000041", "Test1234");
        String refresh = refreshFor(u, true);

        MvcResult r = mockMvc.perform(withBearer(post(URL), refresh))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_TOKEN_TYPE");
    }

    @Test
    @DisplayName("Logout sonrası access token hâlâ geçerliyse logout tekrar 200 dönmeli")
    void logoutTwice_secondCallStillOk() throws Exception {
        User u = createOwner("owner@x.com", "+996700000042", "Test1234");
        refreshFor(u, true);
        String access = accessFor(u);

        mockMvc.perform(withBearer(post(URL), access)).andExpect(status().isOk());
        mockMvc.perform(withBearer(post(URL), access)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Logout MANAGER için de çalışır")
    void manager_canLogout() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000043", "Test1234");
        User manager = createManager("mgr@x.com", "+996700000044", "Test1234", owner);
        String access = accessFor(manager);

        mockMvc.perform(withBearer(post(URL), access)).andExpect(status().isOk());
    }
}
