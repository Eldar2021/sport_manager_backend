package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InviteCodeResponse {
    private String code;
    private String expiresAt;
}
