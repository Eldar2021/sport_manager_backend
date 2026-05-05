package kg.sportmanager.repository;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.repository.projection.ManagerStatProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportsRepository extends JpaRepository<Session, UUID> {

    // ─── Overview / общий зал ───────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(s.totalAmount), 0)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long sumRevenue(@Param("venue") Venue venue,
                    @Param("from") Instant from,
                    @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long countCompleted(@Param("venue") Venue venue,
                        @Param("from") Instant from,
                        @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.status = 'CANCELLED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long countCancelled(@Param("venue") Venue venue,
                        @Param("from") Instant from,
                        @Param("to") Instant to);

    // ─── По столу ───────────────────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(s.totalAmount), 0)
            FROM Session s
            WHERE s.table = :table
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long sumRevenueByTable(@Param("table") Tables table,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s)
            FROM Session s
            WHERE s.table = :table
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long countCompletedByTable(@Param("table") Tables table,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    /**
     * Revenue по каждому столу зала за период — для расчёта deltaPercent.
     * Возвращает Map<tableId, revenue>.
     */
    @Query("""
            SELECT s.table.id AS tableId, COALESCE(SUM(s.totalAmount), 0) AS revenue
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            GROUP BY s.table.id
            """)
    List<Object[]> revenueByTableRaw(@Param("venue") Venue venue,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    default Map<UUID, Long> revenueByTable(Venue venue, Instant from, Instant to) {
        Map<UUID, Long> result = new java.util.HashMap<>();
        revenueByTableRaw(venue, from, to).forEach(row -> result.put((UUID) row[0], (Long) row[1]));
        return result;
    }

    // ─── По менеджеру ───────────────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(s.totalAmount), 0)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.manager = :manager
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long sumRevenueByManager(@Param("venue") Venue venue,
                             @Param("manager") User manager,
                             @Param("from") Instant from,
                             @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.manager = :manager
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long countCompletedByManager(@Param("venue") Venue venue,
                                 @Param("manager") User manager,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s)
            FROM Session s
            WHERE s.table.venue = :venue
              AND s.manager = :manager
              AND s.status = 'CANCELLED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    long countCancelledByManager(@Param("venue") Venue venue,
                                 @Param("manager") User manager,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    /**
     * Агрегированная статистика по всем менеджерам зала за период.
     */
    @Query("""
        SELECT
            s.manager.id        AS managerId,
            s.manager.name      AS managerName,
            s.manager.email     AS username,
            COALESCE(SUM(CASE WHEN s.status = 'COMPLETED' THEN s.totalAmount ELSE 0 END), 0) AS revenue,
            COUNT(CASE WHEN s.status = 'COMPLETED' THEN 1 END)   AS sessions,
            COUNT(CASE WHEN s.status = 'CANCELLED' THEN 1 END)   AS cancelCount
        FROM Session s
        WHERE s.table.venue = :venue
          AND s.startedAt >= :from
          AND s.startedAt < :to
          AND s.manager IS NOT NULL
        GROUP BY s.manager.id, s.manager.name, s.manager.email
        """)
    List<ManagerStatProjection> managerStats(@Param("venue") Venue venue,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);
    /**
     * Лог сессий менеджера — последние limit записей по startedAt DESC.
     */
    @Query("""
            SELECT s FROM Session s
            WHERE s.table.venue = :venue
              AND s.manager = :manager
              AND s.startedAt >= :from
              AND s.startedAt < :to
            ORDER BY s.startedAt DESC
            LIMIT :limit
            """)
    List<Session> findSessionLog(@Param("venue") Venue venue,
                                 @Param("manager") User manager,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("limit") int limit);

    /**
     * COMPLETED-сессии стола для heatmap.
     */
    @Query("""
            SELECT s FROM Session s
            WHERE s.table = :table
              AND s.status = 'COMPLETED'
              AND s.startedAt >= :from
              AND s.startedAt < :to
            """)
    List<Session> findCompletedByTableAndRange(@Param("table") Tables table,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);

    /**
     * Поиск менеджера по id (строка).
     */
    @Query("SELECT u FROM User u WHERE CAST(u.id AS string) = :id")
    Optional<User> findManagerById(@Param("id") String id);
}