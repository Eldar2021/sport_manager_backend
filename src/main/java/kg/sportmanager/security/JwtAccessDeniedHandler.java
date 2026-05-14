package kg.sportmanager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json; charset=utf-8");

        Map<String, Object> body = new HashMap<>();
        body.put("code", "FORBIDDEN");
        body.put("message", Map.of(
                "en", "Access denied",
                "ru", "Нет прав на это действие",
                "ky", "Бул аракетке укук жок"
        ));
        body.put("details", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}