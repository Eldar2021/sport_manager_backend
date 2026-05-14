package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SelectVenueRequest {
    @NotBlank
    private String venueId;
}