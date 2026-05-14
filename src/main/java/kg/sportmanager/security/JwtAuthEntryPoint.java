package kg.sportmanager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=utf-8");

        // JwtAuthFilter может пометить request "jwt.error" = "SESSION_EXPIRED"/"INVALID_TOKEN_TYPE"
        Object marker = request.getAttribute("jwt.error");
        String code = marker != null ? marker.toString() : "UNAUTHORIZED";

        Map<String, Map<String, String>> messages = Map.of(
                "SESSION_EXPIRED", Map.of(
                        "en", "Session expired, please login again",
                        "ru", "Сессия истекла, войдите снова",
                        "ky", "Сессия мөөнөтү бүттү, кайра кириңиз"),
                "INVALID_TOKEN_TYPE", Map.of(
                        "en", "Invalid token type",
                        "ru", "Неверный тип токена",
                        "ky", "Токен түрү жараксыз"),
                "UNAUTHORIZED", Map.of(
                        "en", "Token is missing, invalid or expired",
                        "ru", "Токен отсутствует, недействителен или истёк",
                        "ky", "Токен жок, жараксыз же мөөнөтү бүткөн")
        );

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", messages.getOrDefault(code, messages.get("UNAUTHORIZED")));
        body.put("details", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}