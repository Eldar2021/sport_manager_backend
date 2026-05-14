package kg.sportmanager.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ForgotPasswordApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/forgot-password";

    @Test
    @DisplayName("MVP: herhangi bir email → 503 SERVICE_UNAVAILABLE envelope")
    void anyEmail_returns503() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL, Map.of("email", "anyone@x.com")))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        assertErrorEnvelope(body(r), "SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Bozuk email → 422 VALIDATION_ERROR")
    void badEmail_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of("email", "not-an-email")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Boş email → 422 VALIDATION_ERROR")
    void emptyEmail_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of("email", "")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Missing email field → 422")
    void missingEmail_returns422() throws Exception {
        mockMvc.perform(postJson(URL, Map.of()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Forgot-password public endpoint — auth header gerekmiyor")
    void isPublicEndpoint() throws Exception {
        // 503 dönmesi auth gate'i geçtiğini doğrular
        mockMvc.perform(postJson(URL, Map.of("email", "x@x.com")))
                .andExpect(status().isServiceUnavailable());
    }
}
