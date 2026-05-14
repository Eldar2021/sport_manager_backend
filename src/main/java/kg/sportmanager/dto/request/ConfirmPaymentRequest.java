package kg.sportmanager.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ConfirmPaymentRequest {
    /** PAID | FAILED — mock-режим симулирует ответ платёжной системы. */
    @NotNull
    @Pattern(regexp = "PAID|FAILED")
    private String outcome;
}
