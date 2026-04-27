package kg.sportmanager.repository;

import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TableRepository extends JpaRepository<Tables, UUID> {

    List<Tables> findByVenueAndDeletedAtIsNullOrderByNumberAsc(Venue venue);

    Optional<Tables> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByVenueAndNumberAndDeletedAtIsNull(Venue venue, Integer number);

    boolean existsByVenueAndNumberAndIdNotAndDeletedAtIsNull(Venue venue, Integer number, UUID id);

    boolean existsByVenueAndDeletedAtIsNull(Venue venue);
}