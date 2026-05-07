package kg.sportmanager.service.impl;

import kg.sportmanager.dto.response.reports.*;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.repository.ReportsRepository;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
import kg.sportmanager.service.ReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportsServiceImpl implements ReportsService {

    private final VenueRepository venueRepository;
    private final TableRepository tableRepository;
    private final SessionRepository sessionRepository;
    private final ReportsRepository reportsRepository;

    // ─────────────────────────────────────────────────────────────
    // 1. GET /reports/venues
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<ReportVenueResponse> getVenues(User user) {
        requireOwner(user);
        return venueRepository
                .findByOwnerAndDeletedAtIsNullOrderByNumberAscCreatedAtAsc(user)
                .stream()
                .map(v -> ReportVenueResponse.builder()
                        .id(v.getId().toString())
                        .name(v.getName())
                        .number(v.getNumber())
                        .build())
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 2. GET /reports/overview
    // ─────────────────────────────────────────────────────────────

    @Override
    public OverviewResponse getOverview(User user, String period,
                                        Instant from, Instant to,
                                        String venueId, boolean compare) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);

        long revenue = reportsRepository.sumRevenue(venue, from, to);
        long sessions = reportsRepository.countCompleted(venue, from, to);
        long cancelled = reportsRepository.countCancelled(venue, from, to);
        String currency = resolveCurrency(venue);

        OverviewResponse.KpiBlock current = OverviewResponse.KpiBlock.builder()
                .totalRevenue(revenue)
                .totalSessions(sessions)
                .cancelledSessions(cancelled)
                .currency(currency)
                .previous(null)
                .build();

        if (!compare || "TODAY".equalsIgnoreCase(period)) {
            return OverviewResponse.builder()
                    .totalRevenue(revenue)
                    .totalSessions(sessions)
                    .cancelledSessions(cancelled)
                    .currency(currency)
                    .previous(null)
                    .build();
        }

        // Clipped previous
        PeriodRange clip = clippedPrevious(period, from, to);
        long prevRevenue = reportsRepository.sumRevenue(venue, clip.from(), clip.to());
        long prevSessions = reportsRepository.countCompleted(venue, clip.from(), clip.to());
        long prevCancelled = reportsRepository.countCancelled(venue, clip.from(), clip.to());

        OverviewResponse.KpiBlock previous = OverviewResponse.KpiBlock.builder()
                .totalRevenue(prevRevenue)
                .totalSessions(prevSessions)
                .cancelledSessions(prevCancelled)
                .currency(currency)
                .previous(null) // рекурсия не нужна
                .build();

        return OverviewResponse.builder()
                .totalRevenue(revenue)
                .totalSessions(sessions)
                .cancelledSessions(cancelled)
                .currency(currency)
                .previous(previous)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // 3. GET /reports/revenue-series
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<RevenuePointResponse> getRevenueSeries(User user, String period,
                                                       Instant from, Instant to,
                                                       String venueId) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        return buildRevenueSeries(venue, period, from, to);
    }

    // ─────────────────────────────────────────────────────────────
    // 4. GET /reports/tables
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<TableReportRowResponse> getTables(User user, String period,
                                                  Instant from, Instant to,
                                                  String venueId, boolean compare) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        String currency = resolveCurrency(venue);

        List<Tables> tables = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue);

        // Клиппед previous для дельты
        Map<UUID, Long> prevRevMap = new HashMap<>();
        if (compare && !"TODAY".equalsIgnoreCase(period)) {
            PeriodRange clip = clippedPrevious(period, from, to);
            prevRevMap = reportsRepository.revenueByTable(venue, clip.from(), clip.to());
        }

        Map<UUID, Long> finalPrevRevMap = prevRevMap;
        List<TableReportRowResponse> rows = new ArrayList<>();

        for (Tables t : tables) {
            long rev = reportsRepository.sumRevenueByTable(t, from, to);
            long sess = reportsRepository.countCompletedByTable(t, from, to);
            Integer delta = null;
            if (compare && !"TODAY".equalsIgnoreCase(period)) {
                long prev = finalPrevRevMap.getOrDefault(t.getId(), 0L);
                delta = prev > 0 ? (int) Math.round((rev - prev) * 100.0 / prev) : null;
            }
            rows.add(TableReportRowResponse.builder()
                    .tableId(t.getId().toString())
                    .tableName(t.getName())
                    .tableNumber(t.getNumber())
                    .venueId(venue.getId().toString())
                    .venueName(venue.getName())
                    .revenue(rev)
                    .sessions(sess)
                    .currency(currency)
                    .deltaPercent(delta)
                    .build());
        }

        // Сортировка: revenue DESC, tableNumber ASC
        rows.sort(Comparator.comparingLong(TableReportRowResponse::getRevenue).reversed()
                .thenComparingInt(TableReportRowResponse::getTableNumber));
        return rows;
    }

    // ─────────────────────────────────────────────────────────────
    // 5. GET /reports/tables/{id}
    // ─────────────────────────────────────────────────────────────

    @Override
    public TableDetailResponse getTableDetail(User user, String tableId, String period,
                                              Instant from, Instant to,
                                              String venueId, boolean compare) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        Tables table = resolveTable(user, tableId);
        String currency = resolveCurrency(venue);

        long rev = reportsRepository.sumRevenueByTable(table, from, to);
        long sess = reportsRepository.countCompletedByTable(table, from, to);
        Integer delta = null;
        if (compare && !"TODAY".equalsIgnoreCase(period)) {
            PeriodRange clip = clippedPrevious(period, from, to);
            long prev = reportsRepository.sumRevenueByTable(table, clip.from(), clip.to());
            delta = prev > 0 ? (int) Math.round((rev - prev) * 100.0 / prev) : null;
        }

        TableReportRowResponse summary = TableReportRowResponse.builder()
                .tableId(table.getId().toString())
                .tableName(table.getName())
                .tableNumber(table.getNumber())
                .venueId(venue.getId().toString())
                .venueName(venue.getName())
                .revenue(rev)
                .sessions(sess)
                .currency(currency)
                .deltaPercent(delta)
                .build();

        List<RevenuePointResponse> revenueSeries = buildRevenueSeriesByTable(table, period, from, to);
        long[][] hourHeatmap = buildHeatmap(table, from, to);

        return TableDetailResponse.builder()
                .summary(summary)
                .revenueSeries(revenueSeries)
                .hourHeatmap(hourHeatmap)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // 6. GET /reports/managers
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<ManagerReportRowResponse> getManagers(User user, String period,
                                                      Instant from, Instant to,
                                                      String venueId) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        String currency = resolveCurrency(venue);

        return reportsRepository.managerStats(venue, from, to)
                .stream()
                .map(s -> ManagerReportRowResponse.builder()
                        .managerId(s.getManagerId())
                        .name(s.getManagerName())
                        .username(s.getUsername())
                        .revenue(s.getRevenue())
                        .sessions(s.getSessions())
                        .cancelCount(s.getCancelCount())
                        .currency(currency)
                        .build())
                .sorted(Comparator.comparingLong(ManagerReportRowResponse::getRevenue).reversed())
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 7. GET /reports/managers/{id}
    // ─────────────────────────────────────────────────────────────

    @Override
    public ManagerDetailResponse getManagerDetail(User user, String managerId, String period,
                                                  Instant from, Instant to, String venueId) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        String currency = resolveCurrency(venue);

        User manager = resolveManager(managerId);

        long rev = reportsRepository.sumRevenueByManager(venue, manager, from, to);
        long sess = reportsRepository.countCompletedByManager(venue, manager, from, to);
        long cancelCount = reportsRepository.countCancelledByManager(venue, manager, from, to);

        ManagerReportRowResponse summary = ManagerReportRowResponse.builder()
                .managerId(managerId)
                .name(manager.getName())
                .username(manager.getUsername())
                .revenue(rev)
                .sessions(sess)
                .cancelCount(cancelCount)
                .currency(currency)
                .build();

        List<SessionLogEntryResponse> sessionLog = reportsRepository
                .findSessionLog(venue, manager, from, to, 40)
                .stream()
                .map(s -> mapToSessionLogEntry(s, currency))
                .toList();

        return ManagerDetailResponse.builder()
                .summary(summary)
                .sessionLog(sessionLog)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // 8. GET /reports/forecast
    // ─────────────────────────────────────────────────────────────

    @Override
    public ForecastResponse getForecast(User user, String period,
                                        Instant from, Instant to,
                                        String venueId) {
        requireOwner(user);
        Venue venue = resolveVenue(user, venueId);
        String currency = resolveCurrency(venue);

        // Факт: ряд от from до сегодня
        List<RevenuePointResponse> actualSeries = buildRevenueSeries(venue, period, from, to);

        if (actualSeries.size() < 7) {
            throw new AppException("NOT_ENOUGH_DATA", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Линейная регрессия по actualSeries
        int n = actualSeries.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
            y[i] = actualSeries.get(i).getRevenue();
        }
        double[] reg = linearRegression(x, y);
        double slope = reg[0];
        double intercept = reg[1];

        // Конец текущего календарного периода
        Instant periodEnd = calendarPeriodEnd(period, from);
        List<Instant> allBuckets = generateBuckets(period, from, periodEnd);

        long realSoFar = actualSeries.stream().mapToLong(RevenuePointResponse::getRevenue).sum();

        // Индекс последнего фактического бакета
        Set<Instant> actualBuckets = actualSeries.stream()
                .map(RevenuePointResponse::getBucket)
                .collect(Collectors.toSet());

        List<ForecastResponse.ForecastPoint> points = new ArrayList<>();
        long projSum = 0;
        int projIdx = n; // продолжаем индекс регрессии

        for (Instant bucket : allBuckets) {
            if (actualBuckets.contains(bucket)) {
                // Фактический день
                long factRev = actualSeries.stream()
                        .filter(p -> p.getBucket().equals(bucket))
                        .mapToLong(RevenuePointResponse::getRevenue)
                        .findFirst().orElse(0L);
                points.add(ForecastResponse.ForecastPoint.builder()
                        .bucket(bucket)
                        .expected(factRev)
                        .lower(factRev)
                        .upper(factRev)
                        .isProjection(false)
                        .build());
            } else {
                // Проекция
                long expected = Math.max(0, Math.round(slope * projIdx + intercept));
                long lower = Math.round(expected * 0.85);
                long upper = Math.round(expected * 1.15);
                points.add(ForecastResponse.ForecastPoint.builder()
                        .bucket(bucket)
                        .expected(expected)
                        .lower(lower)
                        .upper(upper)
                        .isProjection(true)
                        .build());
                projSum += expected;
                projIdx++;
            }
        }

        long projectedTotal = realSoFar + projSum;

        // Full previous period (не clipped!)
        PeriodRange fullPrev = fullPreviousPeriod(period, from);
        long previousPeriodTotal = reportsRepository.sumRevenue(venue, fullPrev.from(), fullPrev.to());

        return ForecastResponse.builder()
                .points(points)
                .projectedTotal(projectedTotal)
                .previousPeriodTotal(previousPeriodTotal)
                .currency(currency)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ─────────────────────────────────────────────────────────────

    private void requireOwner(User user) {
        if (user.getRole() != User.Role.OWNER) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private Venue resolveVenue(User user, String venueId) {
        UUID id = parseUuid(venueId);
        return venueRepository.findByIdAndDeletedAtIsNull(id)
                .filter(v -> v.getOwner().getId().equals(user.getId()))
                .orElseThrow(() -> new AppException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private Tables resolveTable(User user, String tableId) {
        UUID id = parseUuid(tableId);
        Tables table = tableRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AppException("TABLE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!table.getVenue().getOwner().getId().equals(user.getId())) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        return table;
    }

    private User resolveManager(String managerId) {
        // Предполагаем UserRepository доступен через ReportsRepository или отдельно
        return reportsRepository.findManagerById(managerId)
                .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private String resolveCurrency(Venue venue) {
        // Берём валюту первого стола зала или дефолт KGS
        return tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue)
                .stream()
                .findFirst()
                .map(t -> t.getCurrency() != null ? t.getCurrency().name() : "KGS")
                .orElse("KGS");
    }

    /** Heatmap 7×24 по startedAt сессий стола в диапазоне */
    private long[][] buildHeatmap(Tables table, Instant from, Instant to) {
        long[][] heatmap = new long[7][24];
        List<Session> sessions = sessionRepository.findCompletedByTableAndRange(table, from, to);
        for (Session s : sessions) {
            ZonedDateTime zdt = s.getStartedAt().atZone(ZoneOffset.UTC);
            int dow = zdt.getDayOfWeek().getValue() - 1; // ISO: Пн=1 → 0
            int hour = zdt.getHour();
            heatmap[dow][hour] += s.getTotalAmount() != null ? s.getTotalAmount() : 0L;
        }
        return heatmap;
    }

    /** Серия точек для всего зала */
    private List<RevenuePointResponse> buildRevenueSeries(Venue venue, String period,
                                                          Instant from, Instant to) {
        List<Instant> buckets = generateBuckets(period, from, to);
        return buckets.stream().map(bucket -> {
            Instant bucketEnd = nextBucket(period, bucket);
            long rev = reportsRepository.sumRevenue(venue, bucket, bucketEnd);
            long sess = reportsRepository.countCompleted(venue, bucket, bucketEnd);
            return RevenuePointResponse.builder()
                    .bucket(bucket)
                    .revenue(rev)
                    .sessions(sess)
                    .build();
        }).toList();
    }

    /** Серия точек для одного стола */
    private List<RevenuePointResponse> buildRevenueSeriesByTable(Tables table, String period,
                                                                 Instant from, Instant to) {
        List<Instant> buckets = generateBuckets(period, from, to);
        return buckets.stream().map(bucket -> {
            Instant bucketEnd = nextBucket(period, bucket);
            long rev = reportsRepository.sumRevenueByTable(table, bucket, bucketEnd);
            long sess = reportsRepository.countCompletedByTable(table, bucket, bucketEnd);
            return RevenuePointResponse.builder()
                    .bucket(bucket)
                    .revenue(rev)
                    .sessions(sess)
                    .build();
        }).toList();
    }

    /**
     * Генерация всех бакетов между from и to.
     * YEAR → месячные; остальные → дневные.
     */
    private List<Instant> generateBuckets(String period, Instant from, Instant to) {
        List<Instant> buckets = new ArrayList<>();
        ZonedDateTime cursor = from.atZone(ZoneOffset.UTC);
        ZonedDateTime end = to.atZone(ZoneOffset.UTC);
        boolean isYear = "YEAR".equalsIgnoreCase(period);

        while (cursor.isBefore(end)) {
            buckets.add(cursor.toInstant());
            cursor = isYear ? cursor.plusMonths(1) : cursor.plusDays(1);
        }
        return buckets;
    }

    private Instant nextBucket(String period, Instant bucket) {
        ZonedDateTime zdt = bucket.atZone(ZoneOffset.UTC);
        return "YEAR".equalsIgnoreCase(period)
                ? zdt.plusMonths(1).toInstant()
                : zdt.plusDays(1).toInstant();
    }

    /**
     * Clipped previous: предыдущий календарный период, обрезанный до N дней текущего.
     */
    private PeriodRange clippedPrevious(String period, Instant from, Instant to) {
        long elapsedDays = ChronoUnit.DAYS.between(from, to);
        ZonedDateTime fromZdt = from.atZone(ZoneOffset.UTC);

        Instant prevFrom = switch (period.toUpperCase()) {
            case "WEEK" -> fromZdt.minusWeeks(1).toInstant();
            case "MONTH" -> fromZdt.minusMonths(1).toInstant();
            case "YEAR" -> fromZdt.minusYears(1).toInstant();
            default -> fromZdt.minusDays(elapsedDays).toInstant();
        };

        Instant prevTo = prevFrom.plus(elapsedDays, ChronoUnit.DAYS);
        return new PeriodRange(prevFrom, prevTo);
    }

    /**
     * Full previous period (для forecast).
     */
    private PeriodRange fullPreviousPeriod(String period, Instant from) {
        ZonedDateTime fromZdt = from.atZone(ZoneOffset.UTC);
        return switch (period.toUpperCase()) {
            case "WEEK" -> {
                ZonedDateTime prevMonday = fromZdt.minusWeeks(1);
                yield new PeriodRange(prevMonday.toInstant(), prevMonday.plusWeeks(1).toInstant());
            }
            case "MONTH" -> {
                ZonedDateTime firstOfPrevMonth = fromZdt.minusMonths(1)
                        .withDayOfMonth(1);
                yield new PeriodRange(
                        firstOfPrevMonth.toInstant(),
                        firstOfPrevMonth.plusMonths(1).toInstant()
                );
            }
            case "YEAR" -> {
                ZonedDateTime firstOfPrevYear = fromZdt.minusYears(1)
                        .withDayOfYear(1);
                yield new PeriodRange(
                        firstOfPrevYear.toInstant(),
                        firstOfPrevYear.plusYears(1).toInstant()
                );
            }
            default -> {
                long days = ChronoUnit.DAYS.between(from, calendarPeriodEnd(period, from));
                yield new PeriodRange(fromZdt.minusDays(days).toInstant(), from);
            }
        };
    }

    /**
     * Конец текущего календарного периода (для forecast projection).
     */
    private Instant calendarPeriodEnd(String period, Instant from) {
        ZonedDateTime fromZdt = from.atZone(ZoneOffset.UTC);
        return switch (period.toUpperCase()) {
            case "WEEK" -> {
                // Конец текущей недели (воскресенье 23:59:59 → начало следующего понедельника)
                int dow = fromZdt.getDayOfWeek().getValue(); // 1=Пн, 7=Вс
                yield fromZdt.plusDays(8 - dow).truncatedTo(ChronoUnit.DAYS).toInstant();
            }
            case "MONTH" -> fromZdt.plusMonths(1).withDayOfMonth(1)
                    .truncatedTo(ChronoUnit.DAYS).toInstant();
            case "YEAR" -> fromZdt.plusYears(1).withDayOfYear(1)
                    .truncatedTo(ChronoUnit.DAYS).toInstant();
            default -> fromZdt.plusDays(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        };
    }

    /** Линейная регрессия. Возвращает [slope, intercept]. */
    private double[] linearRegression(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
        }
        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) return new double[]{0, sumY / n};
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return new double[]{slope, intercept};
    }

    private SessionLogEntryResponse mapToSessionLogEntry(Session s, String currency) {
        return SessionLogEntryResponse.builder()
                .sessionId(s.getId().toString())
                .tableId(s.getTable().getId().toString())
                .tableName(s.getTable().getName())
                .tableNumber(s.getTable().getNumber())
                .venueName(s.getTable().getVenue().getName())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .status(s.getStatus().name())
                .currency(currency)
                .durationSeconds(Long.valueOf(s.getDurationSeconds()))
                .totalAmount(s.getTotalAmount())
                .cancelReason(s.getCancelReason())
                .build();
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception e) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    record PeriodRange(Instant from, Instant to) {}
}