package kg.sportmanager.repository;

import kg.sportmanager.entity.InviteCode;
import kg.sportmanager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {
    Optional<InviteCode> findByCodeAndUsedFalse(String code);
    Optional<InviteCode> findByOwner(User owner);

    /** Account-delete cascade: drop all invite codes for given owner. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM InviteCode i WHERE i.owner = :owner")
    int deleteAllByOwner(@Param("owner") User owner);
}
