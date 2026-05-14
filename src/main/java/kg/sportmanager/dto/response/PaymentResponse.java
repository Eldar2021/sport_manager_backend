package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PaymentResponse {
    private String id;
    private String subscriptionId;
    private Long amount;
    private String currency;
    private Integer months;
    private Integer tableCountSnapshot;
    private Integer pricePerTableSnapshot;
    private String status;              // PENDING | PAID | FAILED
    private String paymentUrl;          // null в MOCK
    private String provider;            // MOCK | FINIK
    private String providerPaymentId;   // null в MOCK
    private Instant createdAt;
    private Instant paidAt;
    private Instant failedAt;
    private String failureReason;
}
