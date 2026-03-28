package com.rushorder.inventory.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 재고 예약을 건별로 추적하는 엔티티.
 *
 * <p>Inventory 엔티티가 메뉴 단위의 총량을 관리한다면,
 * StockReservation은 주문 단위의 개별 예약을 추적한다.
 * 이를 통해 TTL 기반 자동 만료, 예약 이력 조회가 가능해진다.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>예약 시 reservedUntil = now + 5분으로 TTL을 설정한다.</li>
 *   <li>스케줄러가 주기적으로 만료된 예약을 조회하여 재고를 해제한다.</li>
 *   <li>idx_reservation_expired 인덱스로 만료 조회 성능을 확보한다.</li>
 * </ul>
 *
 * @see ReservationCleanupScheduler
 */
@Entity
@Table(
        name = "stock_reservations",
        indexes = @Index(
                name = "idx_reservation_expired",
                columnList = "status, reserved_until"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    private static final int RESERVATION_TTL_MINUTES = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long menuId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDateTime reservedAt;

    /**
     * 예약 만료 시각. 이 시각이 지나도 CONFIRMED 상태가 아니면
     * 스케줄러가 자동으로 재고를 해제한다.
     */
    @Column(name = "reserved_until", nullable = false)
    private LocalDateTime reservedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockReservationStatus status;

    public StockReservation(Long menuId, String orderId, int quantity) {
        this.menuId = menuId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.reservedAt = LocalDateTime.now();
        this.reservedUntil = this.reservedAt.plusMinutes(RESERVATION_TTL_MINUTES);
        this.status = StockReservationStatus.RESERVED;
    }

    /** 결제 성공 시 예약을 확정한다. */
    public void confirm() {
        this.status = StockReservationStatus.CONFIRMED;
    }

    /** 명시적 취소 또는 보상 트랜잭션 시 예약을 해제한다. */
    public void release() {
        this.status = StockReservationStatus.RELEASED;
    }

    /** TTL 만료로 스케줄러가 자동 해제할 때 호출된다. */
    public void expire() {
        this.status = StockReservationStatus.EXPIRED;
    }
}
