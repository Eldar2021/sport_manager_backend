package kg.sportmanager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

        Map<String, Object> body = Map.of(
                "code", "UNAUTHORIZED",
                "message", Map.of(
                        "en", "Token is missing, invalid or expired",
                        "ru", "Токен отсутствует, недействителен или истёк",
                        "ky", "Токен жок, жараксыз же мөөнөтү бүткөн"
                ),
                "details", "null"
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}