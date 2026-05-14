package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/login";

    @Test
    @DisplayName("happy path: doğru e-posta + parola → 200 + user + tokens")
    void loginByEmail_happyPath() throws Exception {
        createOwner("owner@x.com", "+996700000001", "Test1234");

        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("owner@x.com", "Test1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("owner@x.com"))
                .andExpect(jsonPath("$.user.role").value("OWNER"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode body = body(r);
        assertThat(body.get("accessToken").asText()).isNotBlank();
        // sanity: refresh token persisted
        User reloaded = userRepository.findByEmail("owner@x.com").orElseThrow();
        assertThat(reloaded.getRefreshToken()).isEqualTo(body.get("refreshToken").asText());
    }

    @Test
    @DisplayName("happy path: doğru telefon + parola → 200")
    void loginByPhone_happyPath() throws Exception {
        createOwner("owner@x.com", "+996700000002", "Test1234");

        mockMvc.perform(postJson(URL, loginPayload("+996700000002", "Test1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.phone").value("+996700000002"));
    }

    @Test
    @DisplayName("yanlış parola → 400 INVALID_CREDENTIALS + envelope (401 sadece expired için)")
    void wrongPassword_returns400Envelope() throws Exception {
        createOwner("owner@x.com", "+996700000003", "Test1234");

        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("owner@x.com", "WrongPass1")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("bilinmeyen kullanıcı → 400 INVALID_CREDENTIALS (kullanıcı sızdırmaz)")
    void unknownUser_returns400() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("nobody@x.com", "Test1234")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("locked hesap → 423 ACCOUNT_LOCKED")
    void lockedAccount_returns423() throws Exception {
        User u = createOwner("locked@x.com", "+996700000004", "Test1234");
        u.setLocked(true);
        userRepository.saveAndFlush(u);

        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("locked@x.com", "Test1234")))
                .andExpect(status().isLocked())
                .andReturn();

        assertErrorEnvelope(body(r), "ACCOUNT_LOCKED");
    }

    @Test
    @DisplayName("boş username → 422 VALIDATION_ERROR + details")
    void emptyUsername_returns422() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, Map.of("username", "", "password", "Test1234")))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        JsonNode b = body(r);
        assertErrorEnvelope(b, "VALIDATION_ERROR");
        assertThat(b.get("details").isArray()).isTrue();
        assertThat(b.get("details").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("boş password → 422 VALIDATION_ERROR")
    void emptyPassword_returns422() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, Map.of("username", "x@x.com", "password", "")))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertErrorEnvelope(body(r), "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("eksik field'lar → 422 VALIDATION_ERROR")
    void missingFields_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("yanlış login sırasında stale Bearer header'ı ignored olmalı → 400 INVALID_CREDENTIALS")
    void wrongLogin_withStaleBearer_returns400Envelope() throws Exception {
        createOwner("owner@x.com", "+996700000005", "Test1234");

        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("owner@x.com", "Wrong"))
                        .header("Authorization", "Bearer not.a.valid.token"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("login response refresh token DB'ye yazılmalı")
    void login_persistsRefreshToken() throws Exception {
        User u = createOwner("owner@x.com", "+996700000006", "Test1234");
        assertThat(u.getRefreshToken()).isNull();

        MvcResult r = mockMvc.perform(postJson(URL, loginPayload("owner@x.com", "Test1234")))
                .andExpect(status().isOk())
                .andReturn();

        String refresh = body(r).get("refreshToken").asText();
        User reloaded = userRepository.findByEmail("owner@x.com").orElseThrow();
        assertThat(reloaded.getRefreshToken()).isEqualTo(refresh);
    }
}
