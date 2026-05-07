package kg.sportmanager.dto.response.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SessionLogEntryResponse {
    private String sessionId;
    private String tableId;
    private String tableName;       // может быть null
    private int tableNumber;
    private String venueName;
    private Instant startedAt;
    private Instant endedAt;
    private String status;          // COMPLETED | CANCELLED
    private String currency;
    private Long durationSeconds;   // null для CANCELLED
    private Long totalAmount;       // null для CANCELLED
    private String cancelReason;    // null для COMPLETED
}
