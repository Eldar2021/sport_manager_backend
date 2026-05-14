package kg.sportmanager.dto.response.reports;

import com.fasterxml.jackson.annotation.JsonProperty;
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

        /**
         * JSON ve mobile sözleşmesi: alan adı {@code isProjection}.
         * Lombok'un {@code @Data} boolean field için ürettiği getter Java Beans
         * convention'ı ile Jackson'da "projection" olarak serialize olur —
         * bu spec'e (docs/reports-api.md ForecastPoint) uymuyor. @JsonProperty
         * ile JSON adını korumayı zorluyoruz.
         */
        @JsonProperty("isProjection")
        private boolean isProjection;
    }
}