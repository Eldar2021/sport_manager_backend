package kg.sportmanager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kg.sportmanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final long LAST_SEEN_THROTTLE_MINUTES = 5L;

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtUtil.check(token) == JwtUtil.TokenStatus.VALID) {
                String type = jwtUtil.extractType(token);
                // Только access-токен пропускаем в SecurityContext — refresh туда не годится.
                if (type == null || JwtUtil.TYPE_ACCESS.equals(type)) {
                    String userId = jwtUtil.extractUserId(token);
                    userRepository.findById(UUID.fromString(userId)).ifPresent(user -> {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        user, null, user.getAuthorities()
                                );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        touchLastSeen(user);
                    });
                } else {
                    SecurityContextHolder.clearContext();
                    request.setAttribute("jwt.error", "INVALID_TOKEN_TYPE");
                }
            } else if (jwtUtil.check(token) == JwtUtil.TokenStatus.EXPIRED) {
                SecurityContextHolder.clearContext();
                request.setAttribute("jwt.error", "SESSION_EXPIRED");
            } else {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Обновляет lastSeenAt пользователя при условии throttle (раз в 5 минут).
     * Используется для Managers API "последний раз в сети".
     */
    private void touchLastSeen(kg.sportmanager.entity.User user) {
        Instant last = user.getLastSeenAt();
        Instant now = Instant.now();
        if (last == null || ChronoUnit.MINUTES.between(last, now) >= LAST_SEEN_THROTTLE_MINUTES) {
            user.setLastSeenAt(now);
            userRepository.save(user);
        }
    }
}
