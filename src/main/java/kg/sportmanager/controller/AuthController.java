package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.request.ForgotPasswordRequest;
import kg.sportmanager.dto.request.LoginRequest;
import kg.sportmanager.dto.request.RefreshTokenRequest;
import kg.sportmanager.dto.request.RegisterRequest;
import kg.sportmanager.dto.response.AuthResponse;
import kg.sportmanager.dto.response.InviteCodeResponse;
import kg.sportmanager.dto.response.TokenPairResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Аутентификация и авторизация")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Вход в систему")
    @ApiResponse(responseCode = "200", description = "Успешный вход, возвращает токены")
    @ApiResponse(responseCode = "401", description = "Неверные учётные данные")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
        log.info("Login attempt for username={}", request.getUsername());
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Регистрация нового пользователя")
    @ApiResponse(responseCode = "200", description = "Успешная регистрация, возвращает токены")
    @ApiResponse(responseCode = "400", description = "Некорректные данные запроса")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
        log.info("Register attempt for email={} role={}", request.getEmail(), request.getRole());
        return ResponseEntity.ok(authService.register(request));
    }

    @Operation(summary = "Обновление access-токена")
    @ApiResponse(responseCode = "200", description = "Новая пара токенов")
    @ApiResponse(responseCode = "401", description = "Refresh-токен недействителен или истёк")
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refresh(@RequestBody @Valid RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @Operation(summary = "Выход из системы")
    @ApiResponse(responseCode = "200", description = "Сессия завершена")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal User user) {
        authService.logout(user);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Сброс пароля",
            description = "MVP: возвращает 503 — email-флоу появится в Phase 5+ (Spring Mail).")
    @ApiResponse(responseCode = "503", description = "Сервис временно недоступен")
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        throw new kg.sportmanager.exception.AppException(
                "SERVICE_UNAVAILABLE",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Operation(summary = "Генерация инвайт-кода", description = "Только для пользователей с ролью OWNER")
    @ApiResponse(responseCode = "200", description = "Инвайт-код сгенерирован")
    @ApiResponse(responseCode = "403", description = "Недостаточно прав (требуется роль OWNER)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/invite-code")
    public ResponseEntity<InviteCodeResponse> inviteCode(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(authService.generateInviteCode(user));
    }
}