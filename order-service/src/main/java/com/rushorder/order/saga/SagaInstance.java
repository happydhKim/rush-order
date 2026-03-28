package com.rushorder.order.saga;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Saga 실행 인스턴스.
 *
 * <p>각 주문에 대한 분산 트랜잭션의 진행 상태를 DB에 영속화한다.
 * 서버가 중간에 죽더라도 이 테이블을 조회하면 미완료 Saga를 찾아 복구할 수 있다.
 *
 * <p>payload에 주문 정보(JSON)를 저장하여, 보상 트랜잭션 시
 * Order 엔티티를 다시 조회하지 않고도 필요한 정보를 얻을 수 있다.
 *
 * <p>[면접 포인트] Saga 상태를 DB에 저장하는 이유:
 * 인메모리로 관리하면 서버 재시작 시 진행 중인 Saga가 유실된다.
 * DB 저장 + 주기적 스캔으로 "stuck saga"를 감지하고 타임아웃 처리할 수 있다.
 */
@Entity
@Table(name = "saga_instances", indexes = {
        @Index(name = "idx_saga_order_id", columnList = "orderId", unique = true),
        @Index(name = "idx_saga_status_updated", columnList = "status, updatedAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance {

    @Id
    @Column(length = 36)
    private String sagaId;

    @Column(nullable = false, unique = true)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    /** 주문 정보 JSON. 보상 트랜잭션 시 재고 해제에 필요한 menuId, quantity 포함 */
    @Column(columnDefinition = "text")
    private String payload;

    /** 보상 재시도 횟수. 최대 3회 초과 시 COMPENSATION_FAILED */
    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public SagaInstance(String sagaId, String orderId, String payload) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.status = SagaStatus.STARTED;
        this.payload = payload;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void startPayment() {
        this.status = SagaStatus.PAYMENT_REQUESTED;
        this.updatedAt = LocalDateTime.now();
    }

    public void completePayment() {
        this.status = SagaStatus.PAYMENT_COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = SagaStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void startCompensation() {
        this.status = SagaStatus.COMPENSATING;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = SagaStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void failCompensation() {
        this.status = SagaStatus.COMPENSATION_FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.updatedAt = LocalDateTime.now();
    }
}
