package kg.sportmanager.service;

import kg.sportmanager.dto.response.reports.*;
import kg.sportmanager.entity.User;

import java.time.Instant;
import java.util.List;

public interface ReportsService {

    List<ReportVenueResponse> getVenues(User user);

    OverviewResponse getOverview(User user, String period, Instant from, Instant to,
                                 String venueId, boolean compare);

    List<RevenuePointResponse> getRevenueSeries(User user, String period, Instant from,
                                                Instant to, String venueId);

    List<TableReportRowResponse> getTables(User user, String period, Instant from,
                                           Instant to, String venueId, boolean compare);

    TableDetailResponse getTableDetail(User user, String tableId, String period,
                                       Instant from, Instant to, String venueId, boolean compare);

    List<ManagerReportRowResponse> getManagers(User user, String period, Instant from,
                                               Instant to, String venueId);

    ManagerDetailResponse getManagerDetail(User user, String managerId, String period,
                                           Instant from, Instant to, String venueId);

    ForecastResponse getForecast(User user, String period, Instant from, Instant to, String venueId);
}