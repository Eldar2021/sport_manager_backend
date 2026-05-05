package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ManagerReportRowResponse {
    private String managerId;
    private String name;
    private String username;
    private long revenue;
    private long sessions;
    private long cancelCount;
    private String currency;
}