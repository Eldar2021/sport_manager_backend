package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionResponse {
    private String id;
    private String tableId;
    private boolean isActive;
    private boolean isPaused;
    private String startedAt;
    private String pausedAt;
    private String resumedAt;
    private Integer totalPausedSeconds;
    private Integer tarifAmountSnapshot;
    private String tarifTypeSnapshot;
}