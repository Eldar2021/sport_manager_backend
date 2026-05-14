package kg.sportmanager.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает write-операцию: до выполнения проверяем, что подписка владельца ACTIVE / GRACE>0.
 * Иначе бросаем AppException("SUBSCRIPTION_REQUIRED", 403).
 *
 * Применяется в SubscriptionGate (Spring AOP). См. review/06-subscription-review.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresActiveSubscription {
}
