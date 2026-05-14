package kg.sportmanager.exception;

import jakarta.servlet.http.HttpServletRequest;
import kg.sportmanager.dto.response.ErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Global exception handler. Single source of truth for the error envelope.
 *
 * Format:
 * <pre>
 * { "code": "...", "message": {"en":"...","ru":"...","ky":"..."}, "details": null | [...] }
 * </pre>
 *
 * Translations are read from messages*.properties via MessageSource.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final Locale KY = Locale.forLanguageTag("ky");

    private final MessageSource messageSource;

    /** AppException — основной канал ошибок (auth, home, session, reports, subscription, managers). */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException ex) {
        return ResponseEntity.status(ex.getStatus()).body(envelope(ex.getCode(), null));
    }

    /** @Valid / Jakarta-validation — заполняет массив details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .rule(fe.getCode() != null ? fe.getCode().toLowerCase() : "invalid")
                        .message(fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid")
                        .build())
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(envelope("VALIDATION_ERROR", details));
    }

    /** Catch-all: на случай неперехваченных исключений. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope("INTERNAL_SERVER_ERROR", null));
    }

    private ErrorResponse envelope(String code, List<ErrorResponse.FieldError> details) {
        Map<String, String> msg = new HashMap<>();
        msg.put("en", resolve(code, EN));
        msg.put("ru", resolve(code, RU));
        msg.put("ky", resolve(code, KY));
        return ErrorResponse.of(code, msg, details);
    }

    private String resolve(String code, Locale locale) {
        try {
            return messageSource.getMessage(code, null, locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}
