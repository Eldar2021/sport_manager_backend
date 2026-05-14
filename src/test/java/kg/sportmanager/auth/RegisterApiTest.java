package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegisterApiTest extends AuthTestSupport {

    private static final String URL = "/api/v1/auth/register";

    @Test
    @DisplayName("OWNER kayıt → 200 + auto-TRIAL subscription oluşur")
    void registerOwner_happyPath_createsTrialSub() throws Exception {
        MvcResult r = mockMvc.perform(postJson(URL,
                        registerOwnerPayload("John", "john@x.com", "+996700000010", "Test1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("OWNER"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        User saved = userRepository.findByEmail("john@x.com").orElseThrow();
        assertThat(saved.getRole()).isEqualTo(User.Role.OWNER);
        assertThat(saved.getHandle()).isNotBlank();
        assertThat(saved.getOwner()).isNull();

        Subscription sub = subscriptionRepository.findByOwner(saved).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(sub.getSource()).isEqualTo(Subscription.Source.TRIAL);
    }

    @Test
    @DisplayName("MANAGER kayıt → geçerli invite ile 200 + invite used=true + owner_id set")
    void registerManager_withValidInvite_succeeds() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000011", "Test1234");
        createActiveTrial(owner);
        createInvite(owner, "INVITE-OK", LocalDateTime.now().plusDays(7), false);

        mockMvc.perform(postJson(URL,
                        registerManagerPayload("Jane", "jane@x.com", "+996700000012", "Test1234", "INVITE-OK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("MANAGER"));

        User manager = userRepository.findByEmail("jane@x.com").orElseThrow();
        assertThat(manager.getOwner()).isNotNull();
        assertThat(manager.getOwner().getId()).isEqualTo(owner.getId());

        var invite = inviteCodeRepository.findByCodeAndUsedFalse("INVITE-OK");
        assertThat(invite).isEmpty(); // used=true artık
    }

    @Test
    @DisplayName("MANAGER kayıt: invite yok → 400 INVALID_INVITE_CODE envelope")
    void registerManager_missingInvite_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Jane");
        body.put("email", "jane@x.com");
        body.put("phone", "+996700000013");
        body.put("password", "Test1234");
        body.put("role", "MANAGER");
        // inviteCode YOK

        MvcResult r = mockMvc.perform(postJson(URL, body))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorEnvelope(body(r), "INVALID_INVITE_CODE");
    }

    @Test
    @DisplayName("MANAGER kayıt: expired invite → 400 INVALID_INVITE_CODE")
    void registerManager_expiredInvite_returns400() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000014", "Test1234");
        createActiveTrial(owner);
        createInvite(owner, "INVITE-EXPIRED", LocalDateTime.now().minusDays(1), false);

        MvcResult r = mockMvc.perform(postJson(URL,
                        registerManagerPayload("Jane", "jane@x.com", "+996700000015", "Test1234", "INVITE-EXPIRED")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorEnvelope(body(r), "INVALID_INVITE_CODE");
    }

    @Test
    @DisplayName("MANAGER kayıt: used invite → 400 INVALID_INVITE_CODE")
    void registerManager_usedInvite_returns400() throws Exception {
        User owner = createOwner("owner@x.com", "+996700000016", "Test1234");
        createActiveTrial(owner);
        createInvite(owner, "INVITE-USED", LocalDateTime.now().plusDays(7), true);

        mockMvc.perform(postJson(URL,
                        registerManagerPayload("Jane", "jane@x.com", "+996700000017", "Test1234", "INVITE-USED")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Duplicate email → 409 EMAIL_ALREADY_USED")
    void duplicateEmail_returns409() throws Exception {
        createOwner("dup@x.com", "+996700000018", "Test1234");

        MvcResult r = mockMvc.perform(postJson(URL,
                        registerOwnerPayload("Other", "dup@x.com", "+996700000019", "Test1234")))
                .andExpect(status().isConflict())
                .andReturn();

        assertErrorEnvelope(body(r), "EMAIL_ALREADY_USED");
    }

    @Test
    @DisplayName("Duplicate phone → 409 PHONE_ALREADY_USED")
    void duplicatePhone_returns409() throws Exception {
        createOwner("a@x.com", "+996700000020", "Test1234");

        MvcResult r = mockMvc.perform(postJson(URL,
                        registerOwnerPayload("Other", "b@x.com", "+996700000020", "Test1234")))
                .andExpect(status().isConflict())
                .andReturn();

        assertErrorEnvelope(body(r), "PHONE_ALREADY_USED");
    }

    @Test
    @DisplayName("Validation: bozuk email → 422")
    void badEmail_returns422() throws Exception {
        mockMvc.perform(postJson(URL,
                        registerOwnerPayload("X", "not-an-email", "+996700000021", "Test1234")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: kısa password → 422")
    void shortPassword_returns422() throws Exception {
        mockMvc.perform(postJson(URL,
                        registerOwnerPayload("X", "ok@x.com", "+996700000022", "short")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: bozuk phone → 422")
    void badPhone_returns422() throws Exception {
        mockMvc.perform(postJson(URL,
                        registerOwnerPayload("X", "ok@x.com", "abc", "Test1234")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Validation: role NULL → 422")
    void nullRole_returns422() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "X");
        body.put("email", "x@x.com");
        body.put("phone", "+996700000023");
        body.put("password", "Test1234");
        // role YOK

        mockMvc.perform(postJson(URL, body))
                .andExpect(status().isUnprocessableEntity());
    }
}
