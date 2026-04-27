package kg.sportmanager.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AppException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }
}