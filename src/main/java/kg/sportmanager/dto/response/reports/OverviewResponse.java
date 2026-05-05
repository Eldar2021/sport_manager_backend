package kg.sportmanager.dto.response.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class OverviewResponse {

    private long totalRevenue;
    private long totalSessions;
    private long cancelledSessions;
    private String currency;
    private KpiBlock previous; // null при compare=false или TODAY

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class KpiBlock {
        private long totalRevenue;
        private long totalSessions;
        private long cancelledSessions;
        private String currency;
        private KpiBlock previous; // всегда null (рекурсия не нужна)
    }
}