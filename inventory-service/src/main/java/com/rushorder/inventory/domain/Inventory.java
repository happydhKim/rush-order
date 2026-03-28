package com.rushorder.inventory.domain;

import com.rushorder.common.exception.InsufficientStockException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 재고 엔티티.
 *
 * <p>메뉴별 재고를 관리하며, 예약(reserved) → 확정(confirm) → 해제(release)의
 * 라이프사이클을 가진다. 동시성 제어는 {@code SELECT ... FOR UPDATE}
 * 비관적 락으로 수행한다.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>{@code totalStock}: 전체 재고 수량. 결제 확정 시 차감된다.</li>
 *   <li>{@code reservedStock}: 예약된(아직 결제 미완료) 수량. 예약 시 증가, 확정/해제 시 감소.</li>
 *   <li>가용 재고 = totalStock - reservedStock</li>
 * </ul>
 *
 * @see com.rushorder.inventory.repository.InventoryRepository#findByMenuIdForUpdate
 */
@Entity
@Table(name = "inventories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long menuId;

    @Column(nullable = false)
    private int totalStock;

    @Column(nullable = false)
    private int reservedStock;

    private LocalDateTime updatedAt;

    public Inventory(Long menuId, int totalStock) {
        this.menuId = menuId;
        this.totalStock = totalStock;
        this.reservedStock = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재고를 예약한다 (주문 접수 시).
     *
     * <p>가용 재고(totalStock - reservedStock)가 부족하면 예외를 발생시킨다.
     * 이 메서드는 반드시 비관적 락이 걸린 상태에서 호출되어야 한다.
     *
     * @param quantity 예약할 수량
     * @throws InsufficientStockException 가용 재고 부족 시
     */
    public void reserve(int quantity) {
        int available = totalStock - reservedStock;
        if (available < quantity) {
            throw new InsufficientStockException(menuId.toString(), available, quantity);
        }
        this.reservedStock += quantity;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 예약을 확정한다 (결제 성공 시).
     *
     * <p>예약 수량을 실제 차감으로 전환한다.
     * totalStock과 reservedStock을 동시에 줄여 정합성을 유지한다.
     *
     * @param quantity 확정할 수량
     */
    public void confirm(int quantity) {
        this.totalStock -= quantity;
        this.reservedStock -= quantity;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 예약을 해제한다 (결제 실패, 타임아웃, 보상 트랜잭션 시).
     *
     * @param quantity 해제할 수량
     */
    public void release(int quantity) {
        this.reservedStock -= quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public int getAvailableStock() {
        return totalStock - reservedStock;
    }
}
