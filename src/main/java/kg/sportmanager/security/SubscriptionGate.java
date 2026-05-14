package kg.sportmanager.security;

import kg.sportmanager.entity.User;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AOP-аспект: до выполнения метода, помеченного {@link RequiresActiveSubscription},
 * проверяем что подписка владельца активна. Иначе — 403 SUBSCRIPTION_REQUIRED.
 *
 * Для MANAGER проверяется подписка его OWNER (User.owner).
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionGate {

    private final SubscriptionService subscriptionService;

    @Before("@annotation(kg.sportmanager.security.RequiresActiveSubscription) "
            + "|| @within(kg.sportmanager.security.RequiresActiveSubscription)")
    public void check() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            // Если фильтр не авторизовал — пусть EntryPoint вернёт 401, не подменяем
            return;
        }

        User owner = (user.getRole() == User.Role.OWNER) ? user : user.getOwner();
        if (owner == null) {
            // Несконфигурированный менеджер без owner — блокируем
            throw new AppException("SUBSCRIPTION_REQUIRED", HttpStatus.FORBIDDEN);
        }

        // getActiveOrThrow сам бросит SUBSCRIPTION_REQUIRED при EXPIRED / GRACE@0
        subscriptionService.getActiveOrThrow(owner);
    }
}
