package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VenueResponse {
    private String id;
    private String name;
    private Integer number;
    private String address;
    private boolean selected;
    private int tableCount;
    private String createdAt;
    private String updatedAt;
}