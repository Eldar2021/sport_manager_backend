package kg.sportmanager.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kg.sportmanager.entity.User;
import lombok.Data;
import lombok.ToString;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(max = 200)
    private String name;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Pattern(regexp = "^\\+?[0-9 ]{8,20}$", message = "Phone must be 8-20 digits, optional leading +")
    private String phone;

    @NotBlank
    @Size(min = 8, max = 100)
    @ToString.Exclude
    private String password;

    @NotNull
    private User.Role role;

    @ToString.Exclude
    @Size(max = 50)
    private String inviteCode;
}
