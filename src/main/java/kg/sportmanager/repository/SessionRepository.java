package kg.sportmanager.repository;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    // ─── Существующие методы ──────────────────────────────────────────

    Optional<Session> findByTableAndIsActiveTrue(Tables table);

    boolean existsByTableAndIsActiveTrue(Tables table);

    boolean existsByTable_VenueAndIsActiveTrue(Venue venue);

    /** Managers API — нельзя удалить менеджера с активной сессией. */
    boolean existsByManagerAndIsActiveTrue(User manager);

    /** Batch lookup активных сессий для нескольких столов сразу — устраняет N+1 на Home. */
    @Query("SELECT s FROM Session s WHERE s.isActive = true AND s.table IN :tables")
    List<Session> findActiveByTables(@Param("tables") List<Tables> tables);

    // ─── Для Reports: heatmap стола ───────────────────────────────────

    @Query("""
            SELECT s FROM Session s
            WHERE s.table = :table
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    List<Session> findCompletedByTableAndRange(
            @Param("table") Tables table,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // ─── Для Reports: лог сессий менеджера (max 40) ───────────────────

    @Query("""
            SELECT s FROM Session s
            WHERE s.table.venue = :venue
              AND s.manager = :manager
              AND s.startedAt >= :from
              AND s.startedAt < :to
            ORDER BY s.startedAt DESC
            LIMIT 40
            """)
    List<Session> findSessionLogByManager(
            @Param("venue") Venue venue,
            @Param("manager") User manager,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Account-delete cascade: drop all sessions for tables in owner's venues. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Session s WHERE s.table.venue.owner = :owner")
    int deleteAllByTableVenueOwner(@Param("owner") User owner);
}
