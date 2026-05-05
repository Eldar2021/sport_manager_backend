package kg.sportmanager.controller;

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
public class ReportsController {

    private final ReportsService reportsService;

    /**
     * GET /reports/venues
     * Список залов владельца для picker'а на экране Reports.
     */
    @GetMapping("/venues")
    public ResponseEntity<List<ReportVenueResponse>> getVenues(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(reportsService.getVenues(user));
    }

    /**
     * GET /reports/overview
     * Сводные KPI: выручка, сессии, отмены + previous (clipped).
     */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> getOverview(
            @AuthenticationPrincipal User user,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getOverview(user, period, from, to, venueId, compare));
    }

    /**
     * GET /reports/revenue-series
     * Дневные / месячные точки выручки для bar chart.
     */
    @GetMapping("/revenue-series")
    public ResponseEntity<List<RevenuePointResponse>> getRevenueSeries(
            @AuthenticationPrincipal User user,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getRevenueSeries(user, period, from, to, venueId));
    }

    /**
     * GET /reports/tables
     * Все столы зала, sorted by revenue DESC.
     */
    @GetMapping("/tables")
    public ResponseEntity<List<TableReportRowResponse>> getTables(
            @AuthenticationPrincipal User user,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getTables(user, period, from, to, venueId, compare));
    }

    /**
     * GET /reports/tables/{id}
     * Детальная страница стола: KPI + revenueSeries + hourHeatmap.
     */
    @GetMapping("/tables/{id}")
    public ResponseEntity<TableDetailResponse> getTableDetail(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getTableDetail(user, id, period, from, to, venueId, compare));
    }

    /**
     * GET /reports/managers
     * Менеджеры зала с COMPLETED-сессиями в периоде, sorted by revenue DESC.
     */
    @GetMapping("/managers")
    public ResponseEntity<List<ManagerReportRowResponse>> getManagers(
            @AuthenticationPrincipal User user,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getManagers(user, period, from, to, venueId));
    }

    /**
     * GET /reports/managers/{id}
     * Детальная страница менеджера: KPI + лог последних 40 сессий.
     */
    @GetMapping("/managers/{id}")
    public ResponseEntity<ManagerDetailResponse> getManagerDetail(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getManagerDetail(user, id, period, from, to, venueId));
    }

    /**
     * GET /reports/forecast
     * Прогноз выручки до конца текущего календарного периода.
     * Мобильный НЕ вызывает этот эндпоинт при period=TODAY.
     */
    @GetMapping("/forecast")
    public ResponseEntity<ForecastResponse> getForecast(
            @AuthenticationPrincipal User user,
            @RequestParam String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String venueId,
            @RequestParam(defaultValue = "true") boolean compare) {
        return ResponseEntity.ok(reportsService.getForecast(user, period, from, to, venueId));
    }
}