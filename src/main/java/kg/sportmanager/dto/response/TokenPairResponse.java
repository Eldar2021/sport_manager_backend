package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * Возвращается только для /auth/refresh — по контракту docs здесь нет user.
 */
@Data
@Builder
public class TokenPairResponse {
    private String accessToken;
    private String refreshToken;
}
