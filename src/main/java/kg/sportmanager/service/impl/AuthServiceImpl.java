package kg.sportmanager.service.impl;

import kg.sportmanager.dto.request.LoginRequest;
import kg.sportmanager.dto.request.RefreshTokenRequest;
import kg.sportmanager.dto.request.RegisterRequest;
import kg.sportmanager.dto.response.AuthResponse;
import kg.sportmanager.dto.response.InviteCodeResponse;
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
import org.springframework.web.server.ResponseStatusException;

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

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailOrPhone(request.getUsername(), request.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS"));

        if (user.isLocked()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "ACCOUNT_LOCKED");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        return buildAuthResponse(user);
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED");
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_USED");
        }

        if (request.getRole() == User.Role.MANAGER) {
            InviteCode invite = inviteCodeRepository
                    .findByCodeAndUsedFalse(request.getInviteCode())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "INVALID_INVITE_CODE"));

            if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE");
            }

            invite.setUsed(true);
            inviteCodeRepository.save(invite);
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .build();

        userRepository.save(user);
        return buildAuthResponse(user);
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        User user = userRepository.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "SESSION_EXPIRED"));

        if (!jwtUtil.isTokenValid(request.getRefreshToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED");
        }

        return buildAuthResponse(user);
    }

    public void logout(User user) {
        user.setRefreshToken(null);
        userRepository.save(user);
    }

    public InviteCodeResponse generateInviteCode(User owner) {
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
