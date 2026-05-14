package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Возвращается при finish (COMPLETED) и cancel (CANCELLED).
 */
@Data
@Builder
public class SessionResultResponse {

    private UUID id;
    private UUID tableId;

    /** Кто стартовал сессию (для аудита/reports). */
    private UUID managerId;

    /** COMPLETED | CANCELLED */
    private String status;

    private Instant startedAt;
    private Instant endedAt;

    // ── Заполнено если COMPLETED, null если CANCELLED ──────────────────
    private Integer durationSeconds;
    private Integer subtotal;
    private Integer discountPercent;
    private Integer totalAmount;

    // ── Заполнено если CANCELLED, null если COMPLETED ──────────────────
    private String cancelReason;
}