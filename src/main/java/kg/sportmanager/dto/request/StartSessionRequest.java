package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StartSessionRequest {
    @NotBlank
    private String tableId;

    /** Opsiyonel. Trim → boşsa NULL; >80 char → 422 INVALID_CUSTOMER_NAME. */
    private String customerName;
}