package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.request.SelectVenueRequest;
import kg.sportmanager.dto.request.TableRequest;
import kg.sportmanager.dto.request.VenueRequest;
import kg.sportmanager.dto.response.*;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.HomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Home", description = "Управление залами и столами")
@SecurityRequirement(name = "bearerAuth")
public class HomePageController {

    private final HomeService homeService;

    @Operation(summary = "Список всех залов пользователя")
    @ApiResponse(responseCode = "200", description = "Список залов")
    @GetMapping("/venue/list")
    public ResponseEntity<List<VenueResponse>> getVenueList(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(homeService.getVenueList(user));
    }

    @Operation(summary = "Получить выбранный зал")
    @ApiResponse(responseCode = "200", description = "Текущий выбранный зал")
    @GetMapping("/venue/selected")
    public ResponseEntity<SelectedVenueResponse> getSelectedVenue(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(homeService.getSelectedVenue(user));
    }

    @Operation(summary = "Изменить выбранный зал")
    @ApiResponse(responseCode = "200", description = "Зал успешно выбран")
    @PatchMapping("/venue/selected")
    public ResponseEntity<SelectedVenueResponse> updateSelectedVenue(
            @AuthenticationPrincipal User user,
            @RequestBody @Valid SelectVenueRequest request) {
        return ResponseEntity.ok(homeService.updateSelectedVenue(user, request));
    }

    @Operation(summary = "Создать зал")
    @ApiResponse(responseCode = "201", description = "Зал успешно создан")
    @ApiResponse(responseCode = "400", description = "Некорректные данные")
    @PostMapping("/venue/create")
    public ResponseEntity<VenueResponse> createVenue(
            @AuthenticationPrincipal User user,
            @RequestBody @Valid VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(homeService.createVenue(user, request));
    }

    @Operation(summary = "Обновить зал")
    @ApiResponse(responseCode = "200", description = "Зал успешно обновлён")
    @ApiResponse(responseCode = "404", description = "Зал не найден")
    @PutMapping("/venue/{id}")
    public ResponseEntity<VenueResponse> updateVenue(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID зала") @PathVariable String id,
            @RequestBody @Valid VenueRequest request) {
        return ResponseEntity.ok(homeService.updateVenue(user, id, request));
    }

    @Operation(summary = "Удалить зал")
    @ApiResponse(responseCode = "200", description = "Зал удалён")
    @ApiResponse(responseCode = "404", description = "Зал не найден")
    @DeleteMapping("/venue/{id}")
    public ResponseEntity<DeleteResponse> deleteVenue(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID зала") @PathVariable String id) {
        return ResponseEntity.ok(homeService.deleteVenue(user, id));
    }

    @Operation(summary = "Создать стол")
    @ApiResponse(responseCode = "201", description = "Стол успешно создан")
    @PostMapping("/table/create")
    public ResponseEntity<TableResponse> createTable(
            @AuthenticationPrincipal User user,
            @RequestBody @Valid TableRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(homeService.createTable(user, request));
    }

    @Operation(summary = "Обновить стол")
    @ApiResponse(responseCode = "200", description = "Стол обновлён")
    @ApiResponse(responseCode = "404", description = "Стол не найден")
    @PutMapping("/table/{id}")
    public ResponseEntity<TableResponse> updateTable(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID стола") @PathVariable String id,
            @RequestBody @Valid TableRequest request) {
        return ResponseEntity.ok(homeService.updateTable(user, id, request));
    }

    @Operation(summary = "Удалить стол")
    @ApiResponse(responseCode = "200", description = "Стол удалён")
    @ApiResponse(responseCode = "404", description = "Стол не найден")
    @DeleteMapping("/table/{id}")
    public ResponseEntity<DeleteResponse> deleteTable(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID стола") @PathVariable String id) {
        return ResponseEntity.ok(homeService.deleteTable(user, id));
    }
}