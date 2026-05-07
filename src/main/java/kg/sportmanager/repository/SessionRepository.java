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
}
