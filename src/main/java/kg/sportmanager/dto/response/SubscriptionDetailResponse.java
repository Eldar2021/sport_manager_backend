package kg.sportmanager.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SubscriptionDetailResponse {
    private SubscriptionResponse subscription;
    private List<PaymentResponse> payments;
}
