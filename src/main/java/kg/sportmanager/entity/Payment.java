package kg.sportmanager.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Tables.Currency currency;

    @Column(nullable = false)
    private Integer months;

    @Column(nullable = false)
    private Integer tableCountSnapshot;

    @Column(nullable = false)
    private Integer pricePerTableSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(length = 2048)
    private String paymentUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Provider provider;

    private String providerPaymentId;

    @CreationTimestamp
    private Instant createdAt;

    private Instant paidAt;

    private Instant failedAt;

    @Column(length = 500)
    private String failureReason;

    public enum Status { PENDING, PAID, FAILED }

    /** Finik зарезервирован для будущей интеграции. Пока поддерживается только MOCK. */
    public enum Provider { MOCK, FINIK }
}
