package kg.sportmanager.dto.request;

import lombok.Data;

@Data
public class FinishSessionRequest {

    /**
     * Скидка в процентах (0–100). Не обязателен — по умолчанию 0.
     */
    private Integer discountPercent = 0;
}