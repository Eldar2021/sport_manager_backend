package kg.sportmanager.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "subscription")
public class SubscriptionConfig {
    private int pricePerTable = 200;
    private String currency = "KGS";
    private int minDurationMonths = 1;
    private int maxDurationMonths = 12;
    private int gracePeriodDays = 5;
    private int freeTrialDays = 14;
    private int expiryWarningDays = 3;
    /** MOCK | FINIK. FINIK не реализован — оставлено как заглушка для будущей интеграции. */
    private String paymentProvider = "MOCK";
}
