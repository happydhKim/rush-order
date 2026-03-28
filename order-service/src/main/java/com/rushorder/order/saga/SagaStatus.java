package com.rushorder.order.saga;

/**
 * Saga 인스턴스의 상태.
 *
 * <p>Order의 {@link com.rushorder.order.domain.OrderStatus}와 별도로 관리한다.
 * OrderStatus는 "주문"의 비즈니스 상태이고, SagaStatus는 "분산 트랜잭션 조율"의
 * 기술적 상태다. 이 분리 덕분에 Saga 로직 변경이 도메인 모델에 영향을 주지 않는다.
 *
 * <p>[면접 포인트] Saga 상태와 도메인 상태를 분리하는 이유:
 * 하나의 enum에 합치면 도메인 전이 규칙과 Saga 제어 흐름이 결합되어
 * 새로운 Step 추가 시 OrderStatus까지 변경해야 한다.
 */
public enum SagaStatus {

    /** Saga 시작됨, 아직 결제 요청 전 */
    STARTED,

    /** 결제 요청 이벤트가 Outbox에 저장됨 */
    PAYMENT_REQUESTED,

    /** 결제 성공 확인됨 */
    PAYMENT_COMPLETED,

    /** 보상 트랜잭션 진행 중 */
    COMPENSATING,

    /** Saga 정상 완료 (주문 확정) */
    COMPLETED,

    /** Saga 실패 (보상 성공, 주문 취소) */
    FAILED,

    /** 보상마저 실패 — DLQ 전송, 수동 처리 필요 */
    COMPENSATION_FAILED
}
