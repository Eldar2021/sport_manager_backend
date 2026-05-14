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
    @DisplayName("happy path: doğru login (email) + newPassword → 200 + yeni token pair + DB'de password değişir")
    void validLogin_returnsNewTokens() throws Exception {
        User u = createOwner("owner@x.com", "+996700000060", "OldPass12");
        String access = accessFor(u);

        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "owner@x.com", "newPassword", "NewPass99")), access))
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
                .andExpect(status().isBadRequest());

        // Login with new password works
        mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "owner@x.com", "password", "NewPass99")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("login telefon olarak gönderildi → 200 (email VEYA phone kabul)")
    void loginByPhone_returnsNewTokens() throws Exception {
        User u = createOwner("owner@x.com", "+996700000061", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "+996700000061", "newPassword", "NewPass99")), access))
                .andExpect(status().isOk());

        mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "+996700000061", "password", "NewPass99")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("login authenticated user'a ait DEĞİL → 400 INVALID_CREDENTIALS (çalınmış token koruması)")
    void wrongLogin_returns400() throws Exception {
        User u = createOwner("owner@x.com", "+996700000062", "Test1234");
        createOwner("other@x.com", "+996700000063", "Test1234");
        String access = accessFor(u);

        // owner@x.com token'ı ile other@x.com'un parolasını değiştirme denemesi
        MvcResult r = mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "other@x.com", "newPassword", "NewPass99")), access))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("auth yok → 400 UNAUTHORIZED (401 sadece expired token için)")
    void noAuth_returns400() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL,
                        Map.of("login", "anyone@x.com", "newPassword", "Test1234")))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(body(r), "UNAUTHORIZED");
    }

    @Test
    @DisplayName("kısa yeni parola → 422 VALIDATION_ERROR")
    void shortNewPassword_returns422() throws Exception {
        User u = createOwner("owner@x.com", "+996700000064", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "owner@x.com", "newPassword", "short")), access))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("boş alanlar → 422")
    void emptyFields_returns422() throws Exception {
        User u = createOwner("owner@x.com", "+996700000065", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "", "newPassword", "")), access))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("login alanı yok → 422 VALIDATION_ERROR")
    void missingLogin_returns422() throws Exception {
        User u = createOwner("owner@x.com", "+996700000066", "OldPass12");
        String access = accessFor(u);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("newPassword", "NewPass99")), access))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("MANAGER da kendi parolasını değiştirebilir")
    void manager_canUpdatePassword() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000067", "Test1234");
        User mgr = createManager("mgr@x.com", "+996700000068", "OldPass12", owner);
        String access = accessFor(mgr);

        mockMvc.perform(withBearer(
                        postJson(URL, Map.of("login", "mgr@x.com", "newPassword", "NewPass99")), access))
                .andExpect(status().isOk());

        mockMvc.perform(postJson("/api/v1/auth/login",
                        Map.of("username", "mgr@x.com", "password", "NewPass99")))
                .andExpect(status().isOk());
    }
}
