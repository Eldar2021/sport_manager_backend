package kg.sportmanager.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VenueRequest {
    @NotBlank
    @Size(min = 1, max = 100)
    private String name;

    @NotNull
    @Min(1)
    private Integer number;

    @Size(max = 255)
    private String address;
}