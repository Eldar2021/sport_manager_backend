package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class RefreshTokenRequest {
    @NotBlank
    @ToString.Exclude
    private String refreshToken;
}
