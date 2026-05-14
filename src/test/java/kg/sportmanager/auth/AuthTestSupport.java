package kg.sportmanager.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kg.sportmanager.entity.InviteCode;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import kg.sportmanager.repository.InviteCodeRepository;
import kg.sportmanager.repository.PaymentRepository;
import kg.sportmanager.repository.SubscriptionRepository;
import kg.sportmanager.repository.UserRepository;
import kg.sportmanager.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Базовый класс для auth integration-тестов. Поднимает полный Spring-контекст
 * с H2 (PG-режим), Hibernate ddl-auto=create-drop и Flyway отключён.
 *
 * MockMvc настраивается вручную через {@link MockMvcBuilders#webAppContextSetup},
 * потому что в Spring Boot 4 убран {@code @AutoConfigureMockMvc}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class AuthTestSupport {

    protected MockMvc mockMvc;
    @Autowired protected WebApplicationContext webAppContext;
    @Autowired protected FilterChainProxy springSecurityFilterChain;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webAppContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Autowired protected UserRepository userRepository;
    @Autowired protected InviteCodeRepository inviteCodeRepository;
    @Autowired protected SubscriptionRepository subscriptionRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtUtil jwtUtil;

    // ─── DB-builders ────────────────────────────────────────────────────────────

    protected User createOwner(String email, String phone, String rawPassword) {
        User owner = User.builder()
                .name("Test Owner")
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(rawPassword))
                .role(User.Role.OWNER)
                .handle(handleFromEmail(email))
                .locked(false)
                .build();
        return userRepository.saveAndFlush(owner);
    }

    protected User createManager(String email, String phone, String rawPassword, User owner) {
        User manager = User.builder()
                .name("Test Manager")
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(rawPassword))
                .role(User.Role.MANAGER)
                .owner(owner)
                .handle(handleFromEmail(email))
                .locked(false)
                .build();
        return userRepository.saveAndFlush(manager);
    }

    protected Subscription createActiveTrial(User owner) {
        java.time.Instant now = java.time.Instant.now();
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .owner(owner)
                .status(Subscription.Status.ACTIVE)
                .source(Subscription.Source.TRIAL)
                .startDate(now)
                .endDate(now.plus(14, java.time.temporal.ChronoUnit.DAYS))
                .build());
    }

    protected InviteCode createInvite(User owner, String code, LocalDateTime expiresAt, boolean used) {
        return inviteCodeRepository.saveAndFlush(InviteCode.builder()
                .code(code)
                .owner(owner)
                .expiresAt(expiresAt)
                .used(used)
                .build());
    }

    protected String handleFromEmail(String email) {
        String base = email == null ? "user" : email.split("@")[0].toLowerCase()
                .replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) base = "user";
        String candidate = base;
        int suffix = 2;
        while (userRepository.existsByHandle(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    // ─── Token helpers ──────────────────────────────────────────────────────────

    protected String accessFor(User user) {
        return jwtUtil.generateAccessToken(user);
    }

    protected String refreshFor(User user, boolean persist) {
        String refresh = jwtUtil.generateRefreshToken(user);
        if (persist) {
            user.setRefreshToken(refresh);
            userRepository.saveAndFlush(user);
        }
        return refresh;
    }

    // ─── HTTP helpers ───────────────────────────────────────────────────────────

    protected MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder postEmpty(String url) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    protected MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder rb, String token) {
        return rb.header("Authorization", "Bearer " + token);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /** Проверяет что тело — стандартный error-envelope с code и тремя языками. */
    protected void assertErrorEnvelope(JsonNode body, String expectedCode) {
        if (!body.has("code") || !body.get("code").asText().equals(expectedCode)) {
            throw new AssertionError("Expected code=" + expectedCode + " in body: " + body);
        }
        if (!body.has("message") || !body.get("message").isObject()) {
            throw new AssertionError("Expected message object with en/ru/ky in body: " + body);
        }
        for (String lang : new String[]{"en", "ru", "ky"}) {
            if (!body.get("message").has(lang)) {
                throw new AssertionError("Missing language '" + lang + "' in message: " + body);
            }
        }
        if (!body.has("details")) {
            throw new AssertionError("Missing 'details' field in envelope: " + body);
        }
    }

    // ─── Request payload builders ───────────────────────────────────────────────

    protected Map<String, Object> loginPayload(String username, String password) {
        return Map.of("username", username, "password", password);
    }

    protected Map<String, Object> registerOwnerPayload(String name, String email, String phone, String password) {
        return Map.of(
                "name", name,
                "email", email,
                "phone", phone,
                "password", password,
                "role", "OWNER");
    }

    protected Map<String, Object> registerManagerPayload(String name, String email, String phone,
                                                         String password, String inviteCode) {
        return Map.of(
                "name", name,
                "email", email,
                "phone", phone,
                "password", password,
                "role", "MANAGER",
                "inviteCode", inviteCode);
    }
}
