package kg.sportmanager.reports;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.session.SessionTestSupport;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Reports integration testleri için ortak helper'lar. Session API testlerinden
 * gelen builder'ları (createVenue, createTable, vb.) miras alır; COMPLETED ve
 * CANCELLED session yaratacak ek metotlar ekler.
 */
public abstract class ReportsTestSupport extends SessionTestSupport {

    /** Belli bir startedAt/endedAt ve totalAmount ile COMPLETED session yaratır. */
    protected Session completedSession(Tables table, User who, Instant startedAt, Instant endedAt,
                                       long totalAmount) {
        int duration = (int) Math.max(0, endedAt.getEpochSecond() - startedAt.getEpochSecond());
        return sessionRepository.saveAndFlush(Session.builder()
                .table(table)
                .manager(who)
                .isActive(false)
                .isPaused(false)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .totalPausedSeconds(0)
                .durationSeconds(duration)
                .tarifAmountSnapshot(table.getTarifAmount())
                .tarifTypeSnapshot(table.getTarifType())
                .status(Session.SessionStatus.COMPLETED)
                .totalAmount(totalAmount)
                .build());
    }

    /** CANCELLED session yaratır (raporda 'cancelCount' artırmak için). */
    protected Session cancelledSession(Tables table, User who, Instant startedAt, String reason) {
        Instant endedAt = startedAt.plusSeconds(30);
        return sessionRepository.saveAndFlush(Session.builder()
                .table(table)
                .manager(who)
                .isActive(false)
                .isPaused(false)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .totalPausedSeconds(0)
                .tarifAmountSnapshot(table.getTarifAmount())
                .tarifTypeSnapshot(table.getTarifType())
                .status(Session.SessionStatus.CANCELLED)
                .cancelReason(reason)
                .build());
    }

    protected MockHttpServletRequestBuilder getWithBearer(String url, String token) {
        return get(url).header("Authorization", "Bearer " + token);
    }
}
