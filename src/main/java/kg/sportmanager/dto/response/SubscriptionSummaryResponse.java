package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Профиль-эндпоинт возвращает только сводку подписки (без всего истории и платежей).
 * Полный объект — {@link SubscriptionResponse} через {@code GET /api/v1/subscription}.
 */
@Data
@Builder
public class SubscriptionSummaryResponse {
    private String status;             // ACTIVE / GRACE / EXPIRED
    private Instant endDate;
    private int daysUntilExpiry;
    private int graceDaysRemaining;
}
