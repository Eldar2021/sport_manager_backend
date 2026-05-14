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

    long countByOwnerAndDeletedAtIsNull(User owner);

    /** Account-delete cascade: drop all venues for given owner (incl. soft-deleted). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM Venue v WHERE v.owner = :owner")
    int deleteAllByOwner(@org.springframework.data.repository.query.Param("owner") User owner);
}