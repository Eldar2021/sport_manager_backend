package kg.sportmanager.service;

import kg.sportmanager.dto.request.CheckoutRequest;
import kg.sportmanager.dto.request.ConfirmPaymentRequest;
import kg.sportmanager.dto.response.PaymentResponse;
import kg.sportmanager.dto.response.SubscriptionDetailResponse;
import kg.sportmanager.dto.response.SubscriptionPricingResponse;
import kg.sportmanager.entity.Subscription;
import kg.sportmanager.entity.User;

public interface SubscriptionService {

    /** GET /subscription — текущая подписка + история платежей. */
    SubscriptionDetailResponse getDetail(User user);

    /** GET /pricing — конфиг + текущая стоимость для этого owner. */
    SubscriptionPricingResponse getPricing(User user);

    /** POST /checkout — создать платёж в статусе PENDING (provider=MOCK). */
    PaymentResponse checkout(User user, CheckoutRequest request);

    /** GET /payment/{id} — polling статуса платежа. */
    PaymentResponse getPayment(User user, String paymentId);

    /** POST /payment/{id}/confirm — mock-only; в проде эндпоинт выключен. */
    PaymentResponse confirmMockPayment(User user, String paymentId, ConfirmPaymentRequest request);

    /** Создаёт TRIAL подписку для нового OWNER (вызывается из register). */
    Subscription createTrial(User owner);

    /**
     * Возвращает актуальный статус подписки (с recompute on read).
     * Используется subscription gate.
     */
    Subscription getActiveOrThrow(User owner);

    /** True если подписка активна (ACTIVE или GRACE с graceDaysRemaining > 0). */
    boolean isActive(User owner);
}
