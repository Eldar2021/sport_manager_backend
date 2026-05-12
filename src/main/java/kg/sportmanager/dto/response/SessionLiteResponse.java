package kg.sportmanager.dto.response;

import kg.sportmanager.entity.Tables;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Возвращается при start / pause / resume.
 * Также используется в карточке стола на Home-экране.
 */
@Data
@Builder
public class SessionLiteResponse {

    private UUID id;
    private UUID tableId;

    /** ACTIVE | PAUSED */
    private String status;

    private Instant startedAt;

    /** Суммарное время всех пауз в секундах (для клиентского таймера). */
    private Integer totalPausedSeconds;

    /** Заполнено если PAUSED, null если ACTIVE. */
    private Instant pausedAt;

    private Integer tarifAmountSnapshot;
    private Tables.TarifType tarifTypeSnapshot;
}