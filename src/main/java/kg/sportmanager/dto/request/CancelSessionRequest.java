package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelSessionRequest {

    @NotBlank
    @Size(min = 1, max = 200)
    private String reason;
}