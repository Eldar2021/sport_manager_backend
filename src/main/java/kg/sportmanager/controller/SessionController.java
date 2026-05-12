package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.request.CancelSessionRequest;
import kg.sportmanager.dto.request.FinishSessionRequest;
import kg.sportmanager.dto.request.StartSessionRequest;
import kg.sportmanager.dto.response.SessionLiteResponse;
import kg.sportmanager.dto.response.SessionResultResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/session")
@RequiredArgsConstructor
@Tag(name = "Session", description = "Управление сессиями столов")
@SecurityRequirement(name = "bearerAuth")
public class SessionController {

    private final SessionService sessionService;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/session/start
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Начать сессию",
            description = "Создаёт новую сессию на свободном столе. Время старта пишет backend."
    )
    @ApiResponse(responseCode = "201", description = "Сессия успешно создана")
    @ApiResponse(responseCode = "404", description = "TABLE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "TABLE_HAS_ACTIVE_SESSION")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN")
    @PostMapping("/start")
    public ResponseEntity<SessionLiteResponse> start(
            @AuthenticationPrincipal User user,
            @RequestBody StartSessionRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(sessionService.start(user, request));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/session/{id}/pause
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Поставить сессию на паузу",
            description = "Останавливает таймер. Время паузы пишет backend. Body пустое."
    )
    @ApiResponse(responseCode = "200", description = "Сессия на паузе")
    @ApiResponse(responseCode = "404", description = "SESSION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "SESSION_NOT_ACTIVE / SESSION_ALREADY_COMPLETED")
    @PostMapping("/{id}/pause")
    public ResponseEntity<SessionLiteResponse> pause(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID сессии") @PathVariable String id) {

        return ResponseEntity.ok(sessionService.pause(user, id));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/session/{id}/resume
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Снять сессию с паузы",
            description = "Возобновляет таймер, накапливает totalPausedSeconds. Body пустое."
    )
    @ApiResponse(responseCode = "200", description = "Сессия возобновлена")
    @ApiResponse(responseCode = "404", description = "SESSION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "SESSION_NOT_PAUSED / SESSION_ALREADY_COMPLETED")
    @PostMapping("/{id}/resume")
    public ResponseEntity<SessionLiteResponse> resume(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID сессии") @PathVariable String id) {

        return ResponseEntity.ok(sessionService.resume(user, id));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/session/{id}/finish
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Завершить сессию",
            description = """
            Принимает оплату и завершает сессию. Время завершения пишет backend.
            Если сессия была на паузе — автоматически resume перед расчётом.
            discountPercent необязателен (по умолчанию 0).
            """
    )
    @ApiResponse(responseCode = "200", description = "Сессия завершена, возвращает итог")
    @ApiResponse(responseCode = "404", description = "SESSION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "SESSION_ALREADY_COMPLETED")
    @ApiResponse(responseCode = "422", description = "INVALID_DISCOUNT")
    @PostMapping("/{id}/finish")
    public ResponseEntity<SessionResultResponse> finish(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID сессии") @PathVariable String id,
            @RequestBody(required = false) FinishSessionRequest request) {

        if (request == null) request = new FinishSessionRequest(); // discountPercent = 0
        return ResponseEntity.ok(sessionService.finish(user, id, request));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/session/{id}/cancel
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Отменить сессию",
            description = """
            Отменяет случайно начатую сессию.
            Manager может отменить только в течение первых 60 секунд.
            Owner — в любое время.
            Отменённые сессии не учитываются в выручке, но видны в аудит-отчётах.
            """
    )
    @ApiResponse(responseCode = "200", description = "Сессия отменена")
    @ApiResponse(responseCode = "404", description = "SESSION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "SESSION_ALREADY_COMPLETED")
    @ApiResponse(responseCode = "422", description = "CANCEL_WINDOW_EXPIRED / VALIDATION_ERROR")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<SessionResultResponse> cancel(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID сессии") @PathVariable String id,
            @RequestBody CancelSessionRequest request) {

        return ResponseEntity.ok(sessionService.cancel(user, id, request));
    }
}