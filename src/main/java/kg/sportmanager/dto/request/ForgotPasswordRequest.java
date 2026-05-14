package kg.sportmanager.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class ForgotPasswordRequest {
    @NotBlank
    @Email
    @ToString.Exclude
    private String email;
}
