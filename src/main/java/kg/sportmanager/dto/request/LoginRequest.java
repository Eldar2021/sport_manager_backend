package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class LoginRequest {
    @NotBlank
    private String username;

    @NotBlank
    @ToString.Exclude
    private String password;
}
