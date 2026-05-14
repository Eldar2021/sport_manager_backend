package kg.sportmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kg.sportmanager.dto.request.CheckoutRequest;
import kg.sportmanager.dto.request.ConfirmPaymentRequest;
import kg.sportmanager.dto.response.PaymentResponse;
import kg.sportmanager.dto.response.SubscriptionDetailResponse;
import kg.sportmanager.dto.response.SubscriptionPricingResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "Подписка владельца (mock-режим до интеграции Finik)")
@SecurityRequirement(name = "bearerAuth")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(summary = "Текущая подписка + история платежей")
    @GetMapping
    public ResponseEntity<SubscriptionDetailResponse> get(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.getDetail(user));
    }

    @Operation(summary = "Конфиг ценообразования + текущее количество столов")
    @GetMapping("/pricing")
    public ResponseEntity<SubscriptionPricingResponse> pricing(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.getPricing(user));
    }

    @Operation(summary = "Создать платёж (PENDING). В mock-режиме paymentUrl=null.")
    @ApiResponse(responseCode = "201", description = "Платёж создан")
    @ApiResponse(responseCode = "422", description = "INVALID_DURATION / NO_TABLES")
    @PostMapping("/checkout")
    public ResponseEntity<PaymentResponse> checkout(
            @AuthenticationPrincipal User user,
            @RequestBody @Valid CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subscriptionService.checkout(user, request));
    }

    @Operation(summary = "Получить статус платежа (polling).")
    @GetMapping("/payment/{id}")
    public ResponseEntity<PaymentResponse> getPayment(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID платежа") @PathVariable String id) {
        return ResponseEntity.ok(subscriptionService.getPayment(user, id));
    }

    @Operation(
            summary = "Симулировать ответ платёжной системы (mock-only).",
            description = "Доступно только при PAYMENT_PROVIDER=MOCK. В проде с Finik отдаст 404."
    )
    @PostMapping("/payment/{id}/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @AuthenticationPrincipal User user,
            @Parameter(description = "ID платежа") @PathVariable String id,
            @RequestBody @Valid ConfirmPaymentRequest request) {
        return ResponseEntity.ok(subscriptionService.confirmMockPayment(user, id, request));
    }
}
