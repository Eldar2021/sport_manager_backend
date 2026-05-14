package kg.sportmanager.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Ответ {@code GET /api/v1/profile}.
 *
 * <ul>
 *     <li>OWNER → {@code profileData} заполнен (venuesCount, managersCount, subscription summary).</li>
 *     <li>MANAGER → {@code profileData = null}.</li>
 * </ul>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ProfileResponse {
    private UserResponse user;
    private ProfileDataResponse profileData;
}
