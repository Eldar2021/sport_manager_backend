package kg.sportmanager.service;

import kg.sportmanager.dto.request.LoginRequest;
import kg.sportmanager.dto.request.RefreshTokenRequest;
import kg.sportmanager.dto.request.RegisterRequest;
import kg.sportmanager.dto.request.UpdatePasswordRequest;
import kg.sportmanager.dto.response.AuthResponse;
import kg.sportmanager.dto.response.InviteCodeResponse;
import kg.sportmanager.dto.response.TokenPairResponse;
import kg.sportmanager.entity.User;

public interface AuthService {

    AuthResponse login(LoginRequest request);

    AuthResponse register(RegisterRequest request);

    /** Возвращает только пару токенов (без user) — по контракту docs. */
    TokenPairResponse refresh(RefreshTokenRequest request);

    void logout(User user);

    InviteCodeResponse generateInviteCode(User owner);

    /** Меняет пароль authenticated-пользователя. Ротация токенов (старый refresh инвалидируется). */
    TokenPairResponse updatePassword(User user, UpdatePasswordRequest request);

    /**
     * App Store / Play Store compliance:
     * <ul>
     *   <li><b>OWNER</b> — cascade hard-delete всех связанных данных (venues, tables,
     *   sessions, invite codes, subscriptions, payments, managers) + own row.</li>
     *   <li><b>MANAGER</b> — soft-delete с анонимизацией PII (email/phone/refresh = null,
     *   deletedAt = now). Идентичность (name, handle) сохраняется, чтобы owner-reports
     *   продолжали работать. Тот же email/phone может перерегистрироваться (partial
     *   uniqueness через null email).</li>
     * </ul>
     */
    void deleteAccount(User user);
}
