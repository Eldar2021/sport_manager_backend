package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class ErrorResponse {
    private String code;
    private Map<String, String> message;

    public static ErrorResponse of(String code, String en, String ru, String ky) {
        Map<String, String> msg = new HashMap<>();
        msg.put("en", en);
        msg.put("ru", ru);
        msg.put("ky", ky);
        return ErrorResponse.builder().code(code).message(msg).build();
    }
}
