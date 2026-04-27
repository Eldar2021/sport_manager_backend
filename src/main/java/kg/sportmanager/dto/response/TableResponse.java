package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TableResponse {
    private String id;
    private String venueId;
    private String name;
    private Integer number;
    private String description;
    private Integer tarifAmount;
    private String currency;
    private String tarifType;
    private SessionResponse session;
    private String createdAt;
    private String updatedAt;
}