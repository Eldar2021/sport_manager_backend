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
import kg.sportmanager.repository.PaymentRepository;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.SubscriptionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.UserRepository;
import kg.sportmanager.repository.VenueRepository;
import kg.sportmanager.security.JwtUtil;
import kg.sportmanager.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kg.sportmanager.exception.AppException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final VenueRepository venueRepository;
    private final TableRepository tableRepository;
    private final SessionRepository sessionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final kg.sportmanager.service.SubscriptionService subscriptionService;

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailOrPhone(request.getUsername(), request.getUsername())
                .orElseThrow(() -> new AppException("INVALID_CREDENTIALS", HttpStatus.BAD_REQUEST));

        if (user.isLocked()) {
            throw new AppException("ACCOUNT_LOCKED", HttpStatus.LOCKED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AppException("INVALID_CREDENTIALS", HttpStatus.BAD_REQUEST);
        }

        return buildAuthResponse(user);
    }

    public AuthResponse register(RegisterRequest request) {
        // Соразмерять только активных (не soft-deleted) пользователей —
        // удалённый аккаунт обнуляет email/phone, и тот же адрес можно
        // зарегистрировать заново.
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.getEmail())) {
            throw new AppException("EMAIL_ALREADY_USED", HttpStatus.CONFLICT);
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(request.getPhone())) {
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
            throw new AppException("INVALID_TOKEN", HttpStatus.BAD_REQUEST);
        }

        JwtUtil.TokenStatus status = jwtUtil.check(request.getRefreshToken());
        if (status == JwtUtil.TokenStatus.EXPIRED) {
            throw new AppException("SESSION_EXPIRED", HttpStatus.UNAUTHORIZED);
        }
        if (status != JwtUtil.TokenStatus.VALID) {
            throw new AppException("INVALID_TOKEN", HttpStatus.BAD_REQUEST);
        }

        // Reject access-tokens sent to /refresh
        if (!JwtUtil.TYPE_REFRESH.equals(jwtUtil.extractType(request.getRefreshToken()))) {
            throw new AppException("INVALID_TOKEN_TYPE", HttpStatus.BAD_REQUEST);
        }

        // Stored token must match the one client sent (rotation prevents reuse of stale tokens)
        User user = userRepository.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(() -> new AppException("INVALID_TOKEN", HttpStatus.BAD_REQUEST));

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
        // /logout is permitAll: identify caller from token if present, otherwise 400.
        // Spec: logout never returns 401 — success → 200, anything else → 400.
        if (user == null) {
            throw new AppException("LOGOUT_FAILED", HttpStatus.BAD_REQUEST);
        }
        user.setRefreshToken(null);
        userRepository.save(user);
    }

    public TokenPairResponse updatePassword(User user, UpdatePasswordRequest request) {
        if (user == null) {
            throw new AppException("UNAUTHORIZED", HttpStatus.BAD_REQUEST);
        }
        // Defansif: gelen login (email/phone) authenticated principal ile eşleşmeli.
        // Aksi halde çalınmış token başka birinin parolasını değiştiremesin.
        String login = request.getLogin();
        boolean matchesEmail = login.equalsIgnoreCase(user.getEmail());
        boolean matchesPhone = login.equals(user.getPhone());
        if (!matchesEmail && !matchesPhone) {
            throw new AppException("INVALID_CREDENTIALS", HttpStatus.BAD_REQUEST);
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

    @Override
    @Transactional
    public void deleteAccount(User user) {
        if (user == null) {
            throw new AppException("UNAUTHORIZED", HttpStatus.BAD_REQUEST);
        }
        if (user.getRole() == User.Role.OWNER) {
            cascadeDeleteOwner(user);
        } else {
            softDeleteManager(user);
        }
    }

    /**
     * Каскадное удаление владельца: уничтожаем все данные, привязанные к нему,
     * в правильном порядке (учёт FK-зависимостей), затем самого владельца.
     *
     * <p>Порядок: payments → subscriptions → sessions → invite codes →
     * tables → venues → managers → owner.
     */
    private void cascadeDeleteOwner(User owner) {
        // Каждый bulk-DELETE использует @Modifying(clearAutomatically=true,
        // flushAutomatically=true), чтобы persistence context не держал stale
        // ссылки между шагами. После последнего clear() сам owner попадает в
        // detached state, поэтому удаляем его через deleteById (bulk-DELETE
        // через id), а не через delete(entity).
        paymentRepository.deleteAllBySubscriptionOwner(owner);
        subscriptionRepository.deleteAllByOwner(owner);
        sessionRepository.deleteAllByTableVenueOwner(owner);
        inviteCodeRepository.deleteAllByOwner(owner);
        tableRepository.deleteAllByVenueOwner(owner);
        venueRepository.deleteAllByOwner(owner);
        userRepository.deleteAllByOwner(owner); // managers (incl. soft-deleted)
        userRepository.deleteById(owner.getId());
        log.info("Cascade-deleted OWNER id={} and all related data", owner.getId());
    }

    /**
     * Soft-delete менеджера: анонимизируем PII (email/phone), сбрасываем
     * refresh, ставим deletedAt. Сохраняем {@code id}, {@code name}, {@code handle}
     * и {@code owner} — чтобы owner-reports / managers-history продолжали
     * корректно отображать историческую активность этого менеджера.
     *
     * <p>Тот же email/phone может зарегистрироваться заново — это будет
     * полностью новый менеджер (новый UUID).
     */
    private void softDeleteManager(User manager) {
        manager.setDeletedAt(Instant.now());
        manager.setEmail(null);
        manager.setPhone(null);
        manager.setRefreshToken(null);
        manager.setLocked(true); // belt-and-suspenders: login невозможен
        userRepository.save(manager);
        log.info("Soft-deleted MANAGER id={} (PII anonymized)", manager.getId());
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
