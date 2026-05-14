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

class UpdatePasswordApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/update-password";

    @Test
    @DisplayName("happy path: doğru eski parola → 200 + yeni token pair + DB'de password değişir")
    void validOldPassword_returnsNewTokens() throws Exception {
        User u = createOwner("owner@x.com", "+996700000060", "OldPass12");
        String access = accessFor(u);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, Map.of("oldPassword", "OldPass12", "newPassword", "NewPass99")), access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode body = body(r);

        // refresh token rotated in DB (assert ÖNCE yapılmalı; sonraki login DB'yi günceller)
        User reloaded = userRepository.findByEmail("owner@x.com").orElseThrow();
        assertThat(reloaded.getRefreshToken()).isEqualTo(body.get("refreshToken").asText());

        // Login with old password fails
        mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "owner@x.com", "password", "OldPass12")))
                .andExpect(status().isUnauthorized());

        // Login with new password works
        mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "owner@x.com", "password", "NewPass99")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("yanlış eski parola → 401 INVALID_CREDENTIALS")
    void wrongOldPassword_returns401() throws Exception {
        User u = createOwner("owner@x.com", "+996700000061", "OldPass12");
        String access = accessFor(u);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, Map.of("oldPassword", "WrongOld!", "newPassword", "NewPass99")), access))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("auth yok → 401 UNAUTHORIZED")
    void noAuth_returns401() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL,
                        Map.of("oldPassword", "X", "newPassword", "Test1234")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("kısa yeni parola → 422 VALIDATION_ERROR")
    void shortNewPassword_returns422() throws Exception {
        User u = createOwner("owner@x.com", "+996700000062", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("oldPassword", "OldPass12", "newPassword", "short")), access))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("boş alanlar → 422")
    void emptyFields_returns422() throws Exception {
        User u = createOwner("owner@x.com", "+996700000063", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("oldPassword", "", "newPassword", "")), access))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("MANAGER da parolasını değiştirebilir")
    void manager_canUpdatePassword() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000064", "Test1234");
        User mgr = createManager("mgr@x.com", "+996700000065", "OldPass12", owner);
        String access = accessFor(mgr);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("oldPassword", "OldPass12", "newPassword", "NewPass99")), access))
                .andExpect(status().isOk());
    }
}
