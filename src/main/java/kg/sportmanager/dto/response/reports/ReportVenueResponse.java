package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReportVenueResponse {
    private String id;
    private String name;
    private int number;
}