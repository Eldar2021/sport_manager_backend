package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ManagerResponse {
    private String id;
    private String name;
    private String username;     // handle
    private Instant lastSeenAt;  // null if never seen
}
