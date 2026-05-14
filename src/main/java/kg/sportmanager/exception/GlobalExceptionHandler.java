package kg.sportmanager.exception;

import jakarta.servlet.http.HttpServletRequest;
import kg.sportmanager.dto.response.ErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * Bozuk JSON body, geçersiz enum değeri, type mismatch vs. — Spring
     * deserialization aşamasında bunları {@link HttpMessageNotReadableException}
     * ile fırlatır. 500 dönmek yerine 400 BAD_REQUEST + envelope döndürürüz.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        log.debug("Unreadable JSON on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("BAD_REQUEST", null));
    }

    /**
     * Zorunlu query-parametresi eksik: 500 yerine 400 + envelope döndürürüz.
     * Reports endpoint'lerinde {@code venueId} / {@code period} / {@code from} / {@code to}
     * eksik gelirse buraya düşer.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest req) {
        log.debug("Missing required param '{}' on {}", ex.getParameterName(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("BAD_REQUEST", null));
    }

    /**
     * Geçersiz query-param tipi (örn: {@code from} olarak bozuk ISO 8601 string).
     * {@link MethodArgumentTypeMismatchException} burada yakalanır → 400 BAD_REQUEST.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        log.debug("Type mismatch for param '{}' on {}: {}",
                ex.getName(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("BAD_REQUEST", null));
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
