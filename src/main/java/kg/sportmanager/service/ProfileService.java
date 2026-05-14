package kg.sportmanager.service;

import kg.sportmanager.dto.response.ProfileResponse;
import kg.sportmanager.entity.User;

public interface ProfileService {

    /** {@code GET /api/v1/profile}. OWNER → полный профиль, MANAGER → user + null. */
    ProfileResponse getProfile(User user);
}
