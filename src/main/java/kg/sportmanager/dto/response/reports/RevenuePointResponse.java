package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RevenuePointResponse {
    private Instant bucket;
    private long revenue;
    private long sessions;
}