package com.rushorder.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 결제 엔티티.
 *
 * <p>외부 PG(Payment Gateway) 승인 결과를 저장한다.
 * 멱등키를 unique constraint로 가지고 있어, Redis 장애 시에도
 * DB 레벨에서 중복 결제를 방지한다.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_order_id", columnList = "orderId"),
        @Index(name = "idx_payment_idempotency_key", columnList = "idempotencyKey", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    /** 외부 PG 트랜잭션 ID */
    private String pgTransactionId;

    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Payment(String orderId, String idempotencyKey, int amount) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void approve(String pgTransactionId) {
        this.status = PaymentStatus.APPROVED;
        this.pgTransactionId = pgTransactionId;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }
}
