package kg.sportmanager.util;

import kg.sportmanager.dto.response.SessionLiteResponse;
import kg.sportmanager.dto.response.SessionResultResponse;
import kg.sportmanager.entity.Session;
import org.springframework.stereotype.Component;

@Component
public class SessionMapper {

    /**
     * Entity → SessionLiteResponse (для ACTIVE / PAUSED состояний).
     */
    public SessionLiteResponse toLite(Session s) {
        // status в ответе: PAUSED если isPaused=true, иначе ACTIVE
        String statusStr = s.isPaused() ? "PAUSED" : "ACTIVE";

        return SessionLiteResponse.builder()
                .id(s.getId())
                .tableId(s.getTable().getId())
                .status(statusStr)
                .startedAt(s.getStartedAt())
                .totalPausedSeconds(s.getTotalPausedSeconds())
                .pausedAt(s.getPausedAt())
                .tarifAmountSnapshot(s.getTarifAmountSnapshot())
                .tarifTypeSnapshot(s.getTarifTypeSnapshot())
                .build();
    }

    /**
     * Entity → SessionResultResponse (для COMPLETED / CANCELLED состояний).
     */
    public SessionResultResponse toResult(Session s, Integer discountPercent, Integer subtotal) {
        Integer total = null;
        if (subtotal != null && discountPercent != null) {
            int discountAmount = Math.round(subtotal * discountPercent / 100f);
            total = subtotal - discountAmount;
        }

        return SessionResultResponse.builder()
                .id(s.getId())
                .tableId(s.getTable().getId())
                .status(s.getStatus().name())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .durationSeconds(s.getDurationSeconds())
                .subtotal(subtotal)
                .discountPercent(s.getStatus() == Session.SessionStatus.COMPLETED ? discountPercent : null)
                .totalAmount(total)
                .cancelReason(s.getCancelReason())
                .build();
    }
}