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
}
