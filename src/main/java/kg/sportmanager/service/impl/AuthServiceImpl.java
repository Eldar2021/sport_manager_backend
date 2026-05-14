package kg.sportmanager.service.impl;

import kg.sportmanager.dto.request.LoginRequest;
import kg.sportmanager.dto.request.RefreshTokenRequest;
import kg.sportmanager.dto.request.RegisterRequest;
import kg.sportmanager.dto.request.UpdatePasswordRequest;
import kg.sportmanager.dto.response.AuthResponse;
import kg.sportmanager.dto.response.InviteCodeResponse;
import kg.sportmanager.dto.response.TokenPairResponse;
import kg.sportmanager.dto.response.UserResponse;
import kg.sportmanager.entity.InviteCode;
import kg.sportmanager.entity.User;
import kg.sportmanager.repository.InviteCodeRepository;
import kg.sportmanager.repository.UserRepository;
import kg.sportmanager.security.JwtUtil;
import kg.sportmanager.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import kg.sportmanager.exception.AppException;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final kg.sportmanager.service.SubscriptionService subscriptionService;

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailOrPhone(request.getUsername(), request.getUsername())
                .orElseThrow(() -> new AppException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED));

        if (user.isLocked()) {
            throw new AppException("ACCOUNT_LOCKED", HttpStatus.LOCKED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AppException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
        }

        return buildAuthResponse(user);
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException("EMAIL_ALREADY_USED", HttpStatus.CONFLICT);
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new AppException("PHONE_ALREADY_USED", HttpStatus.CONFLICT);
        }

        User owner = null;
        if (request.getRole() == User.Role.MANAGER) {
            InviteCode invite = inviteCodeRepository
                    .findByCodeAndUsedFalse(request.getInviteCode())
                    .orElseThrow(() -> new AppException("INVALID_INVITE_CODE", HttpStatus.BAD_REQUEST));

            if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new AppException("INVALID_INVITE_CODE", HttpStatus.BAD_REQUEST);
            }

            invite.setUsed(true);
            inviteCodeRepository.save(invite);
            owner = invite.getOwner();
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .owner(owner)
                .handle(generateUniqueHandle(request.getEmail()))
                .build();

        userRepository.save(user);

        // OWNER получает TRIAL подписку сразу после регистрации
        if (user.getRole() == User.Role.OWNER) {
            subscriptionService.createTrial(user);
        }

        return buildAuthResponse(user);
    }

    /**
     * Генерирует уникальный handle (для @username в API).
     * Берёт local-part email, нормализует, при коллизии добавляет суффикс.
     */
    private String generateUniqueHandle(String email) {
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

    public TokenPairResponse refresh(RefreshTokenRequest request) {
        if (request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new AppException("SESSION_EXPIRED", HttpStatus.UNAUTHORIZED);
        }

        // Stored token must match the one client sent (rotation prevents reuse of stale tokens)
        User user = userRepository.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(() -> new AppException("SESSION_EXPIRED", HttpStatus.UNAUTHORIZED));

        JwtUtil.TokenStatus status = jwtUtil.check(request.getRefreshToken());
        if (status != JwtUtil.TokenStatus.VALID) {
            throw new AppException("SESSION_EXPIRED", HttpStatus.UNAUTHORIZED);
        }

        // Reject access-tokens sent to /refresh
        if (!JwtUtil.TYPE_REFRESH.equals(jwtUtil.extractType(request.getRefreshToken()))) {
            throw new AppException("SESSION_EXPIRED", HttpStatus.UNAUTHORIZED);
        }

        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        user.setRefreshToken(newRefresh);
        userRepository.save(user);

        return TokenPairResponse.builder()
                .accessToken(newAccess)
                .refreshToken(newRefresh)
                .build();
    }

    public void logout(User user) {
        if (user == null) {
            throw new AppException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
        }
        user.setRefreshToken(null);
        userRepository.save(user);
    }

    public TokenPairResponse updatePassword(User user, UpdatePasswordRequest request) {
        if (user == null) {
            throw new AppException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AppException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        user.setRefreshToken(newRefresh);
        userRepository.save(user);
        return TokenPairResponse.builder()
                .accessToken(newAccess)
                .refreshToken(newRefresh)
                .build();
    }

    @kg.sportmanager.security.RequiresActiveSubscription
    public InviteCodeResponse generateInviteCode(User owner) {
        if (owner == null || owner.getRole() != User.Role.OWNER) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        String code = "INVITE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

        InviteCode inviteCode = InviteCode.builder()
                .code(code)
                .owner(owner)
                .expiresAt(expiresAt)
                .build();

        inviteCodeRepository.save(inviteCode);

        return InviteCodeResponse.builder()
                .code(code)
                .expiresAt(expiresAt.toString())
                .build();
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);
        user.setRefreshToken(refreshToken);
        userRepository.save(user);

        return AuthResponse.builder()
                .user(UserResponse.builder()
                        .id(user.getId().toString())
                        .name(user.getName())
                        .role(user.getRole().name())
                        .email(user.getEmail())
                        .phone(user.getPhone())
                        .build())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}
