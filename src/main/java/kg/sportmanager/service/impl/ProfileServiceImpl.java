package kg.sportmanager.service.impl;

import kg.sportmanager.configuration.SubscriptionConfig;
import kg.sportmanager.dto.response.ProfileDataResponse;
import kg.sportmanager.dto.response.ProfileResponse;
import kg.sportmanager.dto.response.SubscriptionSummaryResponse;
import kg.sportmanager.dto.response.UserResponse;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.repository.SubscriptionRepository;
import kg.sportmanager.repository.UserRepository;
import kg.sportmanager.repository.VenueRepository;
import kg.sportmanager.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionConfig subscriptionConfig;

    @Override
    @Transactional
    public ProfileResponse getProfile(User user) {
        if (user == null) {
            // /profile is authenticated route; null shouldn't reach here but defend anyway.
            throw new AppException("UNAUTHORIZED", HttpStatus.BAD_REQUEST);
        }

        UserResponse userDto = UserResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .role(user.getRole().name())
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();

        // Per spec: MANAGER → profileData = null
        ProfileDataResponse profileData = null;
        if (user.getRole() == User.Role.OWNER) {
            profileData = ProfileDataResponse.builder()
                    .venuesCount(venueRepository.countByOwnerAndDeletedAtIsNull(user))
                    .managersCount(userRepository.countByOwnerAndRoleAndDeletedAtIsNull(user, User.Role.MANAGER))
                    .subscription(loadSubscriptionSummary(user))
                    .build();
        }

        return ProfileResponse.builder()
                .user(userDto)
                .profileData(profileData)
                .build();
    }

    /**
     * Возвращает анлий-сводку подписки. Recompute статус на лету (как и
     * {@code GET /subscription} делает), но persist здесь не нужен — это read-only.
     * Если подписки нет в БД — null (мобайл покажет "нет подписки").
     */
    private SubscriptionSummaryResponse loadSubscriptionSummary(User owner) {
        return subscriptionRepository.findByOwner(owner)
                .map(sub -> {
                    Subscription.Status status = recomputeStatusInMemory(sub);
                    return SubscriptionSummaryResponse.builder()
                            .status(status.name())
                            .endDate(sub.getEndDate())
                            .daysUntilExpiry(daysUntilExpiry(sub))
                            .graceDaysRemaining(graceDaysRemaining(sub, status))
                            .build();
                })
                .orElse(null);
    }

    /**
     * In-memory эквивалент {@code SubscriptionServiceImpl.recomputeStatus} —
     * без persist. Мутирует копию статус-поля на основе текущего времени.
     */
    private Subscription.Status recomputeStatusInMemory(Subscription sub) {
        Instant now = Instant.now();
        Subscription.Status status = sub.getStatus();
        if (status == Subscription.Status.ACTIVE
                && sub.getEndDate() != null && sub.getEndDate().isBefore(now)) {
            status = Subscription.Status.GRACE;
        }
        if (status == Subscription.Status.GRACE) {
            Instant graceEnd = sub.getGracePeriodEndsAt() != null
                    ? sub.getGracePeriodEndsAt()
                    : (sub.getEndDate() != null
                            ? sub.getEndDate().plus(subscriptionConfig.getGracePeriodDays(), ChronoUnit.DAYS)
                            : null);
            if (graceEnd != null && graceEnd.isBefore(now)) {
                status = Subscription.Status.EXPIRED;
            }
        }
        return status;
    }

    private int daysUntilExpiry(Subscription sub) {
        if (sub.getEndDate() == null) return 0;
        long days = ChronoUnit.DAYS.between(Instant.now(), sub.getEndDate());
        return (int) Math.max(0, days);
    }

    private int graceDaysRemaining(Subscription sub, Subscription.Status status) {
        if (status != Subscription.Status.GRACE) return 0;
        Instant graceEnd = sub.getGracePeriodEndsAt() != null
                ? sub.getGracePeriodEndsAt()
                : (sub.getEndDate() != null
                        ? sub.getEndDate().plus(subscriptionConfig.getGracePeriodDays(), ChronoUnit.DAYS)
                        : null);
        if (graceEnd == null) return 0;
        long days = ChronoUnit.DAYS.between(Instant.now(), graceEnd);
        return (int) Math.max(0, days);
    }
}
