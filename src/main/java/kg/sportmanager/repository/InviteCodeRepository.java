package kg.sportmanager.repository;

import kg.sportmanager.entity.InviteCode;
import kg.sportmanager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {
    Optional<InviteCode> findByCodeAndUsedFalse(String code);
    Optional<InviteCode> findByOwner(User owner);
}
