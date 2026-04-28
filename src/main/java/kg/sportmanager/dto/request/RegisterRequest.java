package kg.sportmanager.dto.request;

import kg.sportmanager.entity.User;
import lombok.Data;

@Data
public class RegisterRequest {
    private String name;
    private String email;
    private String phone;
    private String password;
    private User.Role role;
    private String inviteCode;
}
