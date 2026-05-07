package kg.sportmanager.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@jakarta.persistence.Table(name = "sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    private Tables table;

    @Column(nullable = false)
    private boolean isActive = true;

    @Column(nullable = false)
    private boolean isPaused = false;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant pausedAt;

    private Instant resumedAt;

    @Column(nullable = false)
    private Integer totalPausedSeconds = 0;

    @Column(nullable = false)
    private Integer tarifAmountSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tables.TarifType tarifTypeSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private User manager;                    // кто начал/завершил сессию

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;           // COMPLETED | CANCELLED | ACTIVE

    private Instant endedAt;                // когда завершилась

    private Integer durationSeconds;        // длительность в секундах (null если CANCELLED)

    private Long totalAmount;               // итоговая сумма (null если CANCELLED)

    private String cancelReason;

    public enum SessionStatus {
        ACTIVE, COMPLETED, CANCELLED
    }
}

