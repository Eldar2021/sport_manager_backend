package kg.sportmanager.repository;

import kg.sportmanager.entity.Payment;
import kg.sportmanager.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findBySubscriptionOrderByCreatedAtDesc(Subscription subscription);
}
