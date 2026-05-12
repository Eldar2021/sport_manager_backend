package kg.sportmanager.dto.request;

import lombok.Data;

@Data
public class CancelSessionRequest {

    /** Причина отмены (обязательна, 1–200 символов). */
    private String reason;
}