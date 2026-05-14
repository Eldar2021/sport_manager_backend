package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * Used in "forgot-password → email-temp-pw → login → set personal pw" flow.
 *
 * <p>Endpoint authentication: Bearer (kullanıcı geçici parola ile login olduktan
 * sonra çağırır). {@code oldPassword} sorgulanmaz — kullanıcı zaten geçici
 * parolasını unutmuş olabilir.
 *
 * <p>{@code login} (email veya phone) defansif amaçla istenir: server, authenticated
 * principal'ın email/phone'u ile eşleşmiyorsa {@code 400 INVALID_CREDENTIALS}
 * döner. Bu, çalınmış bir access token ile başka kullanıcının parolasını
 * değiştirmeyi engeller.
 */
@Data
public class UpdatePasswordRequest {
    @NotBlank
    private String login;

    @NotBlank
    @Size(min = 8, max = 100)
    @ToString.Exclude
    private String newPassword;
}
