package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeleteResponse {
    private String id;
    private boolean deleted;
}