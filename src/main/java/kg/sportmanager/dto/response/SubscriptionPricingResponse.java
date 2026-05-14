package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubscriptionPricingResponse {
    private int pricePerTable;
    private String currency;
    private int tableCount;
    private long monthlyAmount;
    private int minDurationMonths;
    private int maxDurationMonths;
    private int gracePeriodDays;
    private int freeTrialDays;
    private int expiryWarningDays;
}
