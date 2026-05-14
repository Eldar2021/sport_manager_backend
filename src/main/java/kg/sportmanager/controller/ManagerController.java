package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.response.ManagerResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.ManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/managers")
@RequiredArgsConstructor
@Tag(name = "Managers", description = "Управление командой менеджеров владельца")
@SecurityRequirement(name = "bearerAuth")
public class ManagerController {

    private final ManagerService managerService;

    @Operation(summary = "Список менеджеров владельца")
    @ApiResponse(responseCode = "200", description = "Список менеджеров")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN — требуется роль OWNER")
    @GetMapping
    public ResponseEntity<List<ManagerResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(managerService.listByOwner(user));
    }

    @Operation(summary = "Исключить менеджера из команды (soft-delete)")
    @ApiResponse(responseCode = "204", description = "Менеджер удалён")
    @ApiResponse(responseCode = "404", description = "MANAGER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "HAS_ACTIVE_SESSION — активные сессии менеджера")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID менеджера") @PathVariable String id) {
        managerService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
