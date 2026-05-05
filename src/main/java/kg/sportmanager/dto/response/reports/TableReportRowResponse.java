package kg.sportmanager.dto.response.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TableReportRowResponse {
    private String tableId;
    private String tableName;       // может быть null
    private int tableNumber;
    private String venueId;
    private String venueName;
    private long revenue;
    private long sessions;
    private String currency;
    private Integer deltaPercent;   // null если compare=false или нет данных
}

