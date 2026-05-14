package kg.sportmanager.home;

import kg.sportmanager.auth.AuthTestSupport;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

/**
 * Base for Home Page integration tests — provides DB-builders for Venue / Tables / Session.
 * Inherits {@link AuthTestSupport} so MockMvc, ObjectMapper, user-builders are reused.
 */
public abstract class HomeTestSupport extends AuthTestSupport {

    @Autowired protected VenueRepository venueRepository;
    @Autowired protected TableRepository tableRepository;
    @Autowired protected SessionRepository sessionRepository;

    protected Venue createVenue(User owner, String name, int number, boolean selected) {
        return venueRepository.saveAndFlush(Venue.builder()
                .owner(owner)
                .name(name)
                .number(number)
                .address("addr-" + number)
                .selected(selected)
                .build());
    }

    protected Venue createDeletedVenue(User owner, String name, int number) {
        Venue v = createVenue(owner, name, number, false);
        v.setDeletedAt(Instant.now());
        return venueRepository.saveAndFlush(v);
    }

    protected Tables createTable(Venue venue, String name, int number,
                                 int tarifAmount, Tables.TarifType type) {
        return tableRepository.saveAndFlush(Tables.builder()
                .venue(venue)
                .name(name)
                .number(number)
                .description(null)
                .tarifAmount(tarifAmount)
                .currency(Tables.Currency.KGS)
                .tarifType(type)
                .build());
    }

    protected Tables createDeletedTable(Venue venue, int number) {
        Tables t = createTable(venue, "deleted-" + number, number, 100, Tables.TarifType.HOUR);
        t.setDeletedAt(Instant.now());
        return tableRepository.saveAndFlush(t);
    }

    /** Bir masada aktif session yaratır (start session). */
    protected Session createActiveSession(Tables table, User manager) {
        return sessionRepository.saveAndFlush(Session.builder()
                .table(table)
                .manager(manager)
                .isActive(true)
                .isPaused(false)
                .startedAt(Instant.now())
                .totalPausedSeconds(0)
                .tarifAmountSnapshot(table.getTarifAmount())
                .tarifTypeSnapshot(table.getTarifType())
                .status(Session.SessionStatus.ACTIVE)
                .build());
    }
}
