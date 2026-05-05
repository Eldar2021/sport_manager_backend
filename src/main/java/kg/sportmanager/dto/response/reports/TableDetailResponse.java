package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TableDetailResponse {
    private TableReportRowResponse summary;
    private List<RevenuePointResponse> revenueSeries;
    private long[][] hourHeatmap; // [7][24] — Пн=0…Вс=6, часы 0…23
}