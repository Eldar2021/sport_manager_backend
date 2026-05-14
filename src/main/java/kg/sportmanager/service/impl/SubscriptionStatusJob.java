package kg.sportmanager.service.impl;

import kg.sportmanager.configuration.SubscriptionConfig;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Cron: ежедневно в 00:00 UTC переводит подписки ACTIVE → GRACE → EXPIRED.
 *
 * Дополнительно, эндпоинт GET /subscription тоже делает recompute on read,
 * так что отставание не больше времени между запросами.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionStatusJob {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionConfig config;

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @Transactional
    public void transitionStatuses() {
        Instant now = Instant.now();
        log.info("Running subscription status transition at {}", now);

        int active2grace = 0;
        for (Subscription s : subscriptionRepository.findByStatus(Subscription.Status.ACTIVE)) {
            if (s.getEndDate().isBefore(now)) {
                s.setStatus(Subscription.Status.GRACE);
                s.setGracePeriodEndsAt(s.getEndDate().plus(config.getGracePeriodDays(), ChronoUnit.DAYS));
                subscriptionRepository.save(s);
                active2grace++;
            }
        }

        int grace2expired = 0;
        for (Subscription s : subscriptionRepository.findByStatus(Subscription.Status.GRACE)) {
            if (s.getGracePeriodEndsAt() != null && s.getGracePeriodEndsAt().isBefore(now)) {
                s.setStatus(Subscription.Status.EXPIRED);
                subscriptionRepository.save(s);
                grace2expired++;
            }
        }

        log.info("Subscription transitions: ACTIVE→GRACE={}, GRACE→EXPIRED={}", active2grace, grace2expired);
    }
}
