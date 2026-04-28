package kg.sportmanager.repository;

import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {

    List<Venue> findByOwnerAndDeletedAtIsNullOrderByNumberAscCreatedAtAsc(User owner);

    Optional<Venue> findByOwnerAndSelectedTrueAndDeletedAtIsNull(User owner);

    Optional<Venue> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByOwnerAndNumberAndDeletedAtIsNull(User owner, Integer number);

    boolean existsByOwnerAndNumberAndIdNotAndDeletedAtIsNull(User owner, Integer number, UUID id);

    Optional<Venue> findFirstByOwnerAndDeletedAtIsNullOrderByCreatedAtAsc(User owner);
}