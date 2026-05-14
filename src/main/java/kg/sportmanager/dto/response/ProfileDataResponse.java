package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * Owner-only payload. Для менеджера это поле в {@link ProfileResponse} = null.
 */
@Data
@Builder
public class ProfileDataResponse {
    private long venuesCount;
    private long managersCount;
    private SubscriptionSummaryResponse subscription;
}
