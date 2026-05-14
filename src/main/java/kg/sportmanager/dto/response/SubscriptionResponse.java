package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SubscriptionResponse {
    private String id;
    private String ownerId;
    private String status;             // ACTIVE | GRACE | EXPIRED
    private String source;             // TRIAL | PAID
    private Instant startDate;
    private Instant endDate;
    private Instant gracePeriodEndsAt;
    private int daysUntilExpiry;
    private int graceDaysRemaining;
    private Instant createdAt;
    private Instant updatedAt;
}
