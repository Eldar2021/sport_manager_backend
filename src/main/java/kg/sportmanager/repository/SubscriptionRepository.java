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
}
