package com.rushorder.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 루트 엔티티.
 *
 * <p>Saga의 대상이 되는 핵심 Aggregate Root.
 * 주문 상태는 Saga Orchestrator가 관리하며, 외부에서 직접 상태를 변경하지 않는다.
 * 상태 전이 규칙:
 * <pre>
 *   PENDING → INVENTORY_RESERVED → PAYMENT_PROCESSING → CONFIRMED
 *                                                     → COMPENSATING → CANCELLED
 *          → CANCELLED (재고 부족으로 즉시 실패)
 * </pre>
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private int totalAmount;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Order(String orderId, Long userId, Long restaurantId, String idempotencyKey) {
        this.orderId = orderId;
        this.userId = userId;
        this.restaurantId = restaurantId;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.totalAmount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.assignOrder(this);
        this.totalAmount += item.getPrice() * item.getQuantity();
    }

    /**
     * Saga 상태 전이. 허용되지 않는 전이를 시도하면 예외를 발생시킨다.
     */
    public void transitionTo(OrderStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    String.format("Cannot transition from %s to %s", this.status, newStatus));
        }
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }
}
