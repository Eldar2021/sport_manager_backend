package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SelectedVenueResponse {
    private VenueResponse venue;
    private List<TableResponse> tables;
}