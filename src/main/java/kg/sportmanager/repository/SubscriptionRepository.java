package kg.sportmanager.repository;

import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByOwner(User owner);

    List<Subscription> findByStatus(Subscription.Status status);

    /** Account-delete cascade: drop all subscriptions for given owner. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM Subscription s WHERE s.owner = :owner")
    int deleteAllByOwner(@org.springframework.data.repository.query.Param("owner") User owner);
}
