package kg.sportmanager.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Стандартный envelope ошибок API.
 *
 * Контракт:
 * <pre>
 * {
 *   "code": "ERROR_CODE",
 *   "message": { "en": "...", "ru": "...", "ky": "..." },
 *   "details": null | [ { "field": "...", "rule": "...", "message": "..." } ]
 * }
 * </pre>
 */
@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ErrorResponse {
    private String code;
    private Map<String, String> message;

    /** null или список ошибок поля (для validation). */
    private List<FieldError> details;

    public static ErrorResponse of(String code, String en, String ru, String ky) {
        Map<String, String> msg = new HashMap<>();
        msg.put("en", en);
        msg.put("ru", ru);
        msg.put("ky", ky);
        return ErrorResponse.builder().code(code).message(msg).details(null).build();
    }

    public static ErrorResponse of(String code, Map<String, String> message, List<FieldError> details) {
        return ErrorResponse.builder().code(code).message(message).details(details).build();
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String rule;
        private String message;
    }
}
