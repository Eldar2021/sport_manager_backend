package kg.sportmanager.repository;

import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByTableAndIsActiveTrue(Tables table);

    boolean existsByTableAndIsActiveTrue(Tables table);

    boolean existsByTable_VenueAndIsActiveTrue(kg.sportmanager.entity.Venue venue);
}