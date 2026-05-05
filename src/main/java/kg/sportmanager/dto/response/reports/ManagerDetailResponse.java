package kg.sportmanager.dto.response.reports;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ManagerDetailResponse {
    private ManagerReportRowResponse summary;
    private List<SessionLogEntryResponse> sessionLog; // не более 40
}