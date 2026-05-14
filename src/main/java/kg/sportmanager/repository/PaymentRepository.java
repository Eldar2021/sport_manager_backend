package kg.sportmanager.repository;

import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findBySubscriptionOrderByCreatedAtDesc(Subscription subscription);

    /** Account-delete cascade: drop all payments belonging to owner's subscription(s). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Payment p WHERE p.subscription.owner = :owner")
    int deleteAllBySubscriptionOwner(@Param("owner") User owner);
}
