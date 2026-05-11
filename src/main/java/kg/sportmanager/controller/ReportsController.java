package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kg.sportmanager.dto.response.reports.*;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.ReportsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Отчёты и аналитика для владельца зала")
@SecurityRequirement(name = "bearerAuth")
public class ReportsController {

    private final ReportsService reportsService;

    @Operation(summary = "Список залов для picker'а на экране Reports")
    @ApiResponse(responseCode = "200", description = "Список залов владельца")
    @GetMapping("/venues")
    public ResponseEntity<List<ReportVenueResponse>> getVenues(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(reportsService.getVenues(user));
    }

    @Operation(summary = "Сводные KPI", description = "Выручка, сессии, отмены + previous (clipped)")
    @ApiResponse(responseCode = "200", description = "Сводные показатели за период")
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> getOverview(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Период: TODAY / WEEK / MONTH / YEAR") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @Parameter(description = "Включить сравнение с предыдущим периодом") @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getOverview(user, period, from, to, venueId, compare));
    }

    @Operation(summary = "Данные для bar chart выручки", description = "Дневные / месячные точки выручки")
    @ApiResponse(responseCode = "200", description = "Список точек выручки")
    @GetMapping("/revenue-series")
    public ResponseEntity<List<RevenuePointResponse>> getRevenueSeries(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getRevenueSeries(user, period, from, to, venueId));
    }

    @Operation(summary = "Все столы зала", description = "Отсортированы по выручке по убыванию")
    @ApiResponse(responseCode = "200", description = "Список столов с показателями")
    @GetMapping("/tables")
    public ResponseEntity<List<TableReportRowResponse>> getTables(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getTables(user, period, from, to, venueId, compare));
    }

    @Operation(summary = "Детальная страница стола", description = "KPI + revenueSeries + hourHeatmap")
    @ApiResponse(responseCode = "200", description = "Детальная информация по столу")
    @ApiResponse(responseCode = "404", description = "Стол не найден")
    @GetMapping("/tables/{id}")
    public ResponseEntity<TableDetailResponse> getTableDetail(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID стола") @PathVariable String id,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getTableDetail(user, id, period, from, to, venueId, compare));
    }

    @Operation(summary = "Менеджеры зала", description = "Только с COMPLETED-сессиями, отсортированы по выручке")
    @ApiResponse(responseCode = "200", description = "Список менеджеров с показателями")
    @GetMapping("/managers")
    public ResponseEntity<List<ManagerReportRowResponse>> getManagers(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getManagers(user, period, from, to, venueId));
    }

    @Operation(summary = "Детальная страница менеджера", description = "KPI + лог последних 40 сессий")
    @ApiResponse(responseCode = "200", description = "Детальная информация по менеджеру")
    @ApiResponse(responseCode = "404", description = "Менеджер не найден")
    @GetMapping("/managers/{id}")
    public ResponseEntity<ManagerDetailResponse> getManagerDetail(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID менеджера") @PathVariable String id,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getManagerDetail(user, id, period, from, to, venueId));
    }

    @Operation(
            summary = "Прогноз выручки",
            description = "До конца текущего календарного периода. Не вызывается при period=TODAY"
    )
    @ApiResponse(responseCode = "200", description = "Прогноз выручки")
    @GetMapping("/forecast")
    public ResponseEntity<ForecastResponse> getForecast(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Период") @RequestParam String period,
            @Parameter(description = "Начало периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Конец периода (ISO 8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "ID зала") @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getForecast(user, period, from, to, venueId));
    }
}