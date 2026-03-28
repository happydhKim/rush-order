package com.rushorder.inventory.domain;

/**
 * 재고 예약의 상태를 나타내는 열거형.
 *
 * <p>상태 전이:
 * RESERVED → CONFIRMED (결제 성공)
 * RESERVED → RELEASED  (결제 실패, 보상 트랜잭션)
 * RESERVED → EXPIRED   (TTL 만료, 스케줄러가 자동 해제)
 */
public enum StockReservationStatus {

    /** 예약됨 — 결제 대기 중 */
    RESERVED,

    /** 확정됨 — 결제 성공으로 재고 차감 완료 */
    CONFIRMED,

    /** 해제됨 — 명시적 취소 또는 보상 트랜잭션 */
    RELEASED,

    /** 만료됨 — TTL 초과로 스케줄러가 자동 해제 */
    EXPIRED
}
