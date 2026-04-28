package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {
    private String id;
    private String name;
    private String role;
    private String email;
    private String phone;
}
