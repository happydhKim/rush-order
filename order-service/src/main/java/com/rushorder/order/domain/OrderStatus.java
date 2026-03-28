package com.rushorder.order.domain;

import java.util.Set;

/**
 * 주문 상태.
 *
 * <p>상태 전이 규칙을 enum 내부에 캡슐화하여
 * 잘못된 전이를 컴파일 시점 가까이에서 방지한다.
 */
public enum OrderStatus {

    PENDING {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(INVENTORY_RESERVED, CANCELLED);
        }
    },

    INVENTORY_RESERVED {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(PAYMENT_PROCESSING);
        }
    },

    PAYMENT_PROCESSING {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(CONFIRMED, COMPENSATING);
        }
    },

    CONFIRMED {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(); // 최종 상태
        }
    },

    COMPENSATING {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(CANCELLED, COMPENSATION_FAILED);
        }
    },

    CANCELLED {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(); // 최종 상태
        }
    },

    COMPENSATION_FAILED {
        @Override
        public Set<OrderStatus> allowedTransitions() {
            return Set.of(); // DLQ로 이동, 수동 처리
        }
    };

    public abstract Set<OrderStatus> allowedTransitions();

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }
}
