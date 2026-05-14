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

/**
 * Auth fail entry point.
 *
 * <p><b>Status code контракт (см. требование):</b>
 * <ul>
 *     <li>{@code 401 SESSION_EXPIRED} — только когда access/refresh токен истёк;</li>
 *     <li>{@code 400 INVALID_TOKEN} / {@code INVALID_TOKEN_TYPE} / {@code UNAUTHORIZED} —
 *         все остальные авторизационные ошибки (токен невалиден, неверного типа,
 *         или отсутствует).</li>
 * </ul>
 */
@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        // JwtAuthFilter может пометить request "jwt.error" = SESSION_EXPIRED / INVALID_TOKEN_TYPE / INVALID_TOKEN
        Object marker = request.getAttribute("jwt.error");
        String code = marker != null ? marker.toString() : "UNAUTHORIZED";

        int status = "SESSION_EXPIRED".equals(code)
                ? HttpServletResponse.SC_UNAUTHORIZED      // 401 — только expired
                : HttpServletResponse.SC_BAD_REQUEST;      // 400 — всё остальное

        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");

        Map<String, Map<String, String>> messages = Map.of(
                "SESSION_EXPIRED", Map.of(
                        "en", "Session expired, please login again",
                        "ru", "Сессия истекла, войдите снова",
                        "ky", "Сессия мөөнөтү бүттү, кайра кириңиз"),
                "INVALID_TOKEN_TYPE", Map.of(
                        "en", "Invalid token type",
                        "ru", "Неверный тип токена",
                        "ky", "Токен түрү жараксыз"),
                "INVALID_TOKEN", Map.of(
                        "en", "Invalid or malformed token",
                        "ru", "Неверный или повреждённый токен",
                        "ky", "Токен жараксыз же бузук"),
                "UNAUTHORIZED", Map.of(
                        "en", "Authentication required",
                        "ru", "Требуется авторизация",
                        "ky", "Авторизация талап кылынат")
        );

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", messages.getOrDefault(code, messages.get("UNAUTHORIZED")));
        body.put("details", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
