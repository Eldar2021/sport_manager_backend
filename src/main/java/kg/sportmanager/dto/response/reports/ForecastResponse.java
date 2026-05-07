package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ForecastResponse {
    private List<ForecastPoint> points;
    private long projectedTotal;
    private long previousPeriodTotal;
    private String currency;

    @Data
    @Builder
    public static class ForecastPoint {
        private Instant bucket;
        private long expected;
        private long lower;
        private long upper;
        private boolean isProjection;
    }
}