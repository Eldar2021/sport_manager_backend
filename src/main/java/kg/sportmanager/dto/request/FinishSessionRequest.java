package kg.sportmanager.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class FinishSessionRequest {

    /** Скидка в процентах (0–100). Не обязателен — по умолчанию 0. */
    @Min(0)
    @Max(100)
    private Integer discountPercent = 0;
}