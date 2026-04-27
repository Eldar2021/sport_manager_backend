package kg.sportmanager.exception;

import kg.sportmanager.dto.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<String, Map<String, String>> MESSAGES = Map.ofEntries(
            Map.entry("VENUE_NOT_FOUND", Map.of(
                    "en", "Selected venue not found",
                    "ru", "Выбранное место не найдено",
                    "ky", "Тандалган мекен табылган жок"
            )),
            Map.entry("TABLE_NOT_FOUND", Map.of(
                    "en", "Table not found",
                    "ru", "Стол не найден",
                    "ky", "Стол табылган жок"
            )),
            Map.entry("VENUE_NUMBER_TAKEN", Map.of(
                    "en", "This venue number is already taken",
                    "ru", "Этот номер места уже используется",
                    "ky", "Бул мекен номери буга чейин колдонулган"
            )),
            Map.entry("TABLE_NUMBER_TAKEN", Map.of(
                    "en", "This table number already exists in this venue",
                    "ru", "Стол с таким номером уже есть в этом месте",
                    "ky", "Бул номердеги стол мекенде буга чейин бар"
            )),
            Map.entry("VENUE_HAS_TABLES", Map.of(
                    "en", "Venue has tables, delete them first",
                    "ru", "В месте есть столы, сначала удалите их",
                    "ky", "Мекенде столдор бар, алгач аларды өчүрүңүз"
            )),
            Map.entry("TABLE_HAS_ACTIVE_SESSION", Map.of(
                    "en", "Table has an active session, cannot delete",
                    "ru", "На столе активна сессия, удалить нельзя",
                    "ky", "Столдо активдүү сессия бар, өчүрүүгө болбойт"
            )),
            Map.entry("FORBIDDEN", Map.of(
                    "en", "Access denied",
                    "ru", "Нет прав на это действие",
                    "ky", "Бул аракетке укук жок"
            )),
            Map.entry("VALIDATION_ERROR", Map.of(
                    "en", "Validation failed",
                    "ru", "Ошибка валидации",
                    "ky", "Валидация катасы"
            ))
    );

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handle(AppException ex) {
        Map<String, String> msg = MESSAGES.getOrDefault(ex.getCode(),
                Map.of("en", ex.getCode(), "ru", ex.getCode(), "ky", ex.getCode()));

        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(),
                        msg.get("en"), msg.get("ru"), msg.get("ky")));
    }
}