package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.response.ProfileResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Профиль текущего пользователя")
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "Профиль текущего пользователя",
            description = "OWNER → user + profileData (venuesCount, managersCount, subscription summary). "
                    + "MANAGER → user + profileData=null.")
    @ApiResponse(responseCode = "200", description = "Профиль")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileService.getProfile(user));
    }
}
