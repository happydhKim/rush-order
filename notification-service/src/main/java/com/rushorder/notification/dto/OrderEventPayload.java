package com.rushorder.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Kafka에서 수신한 주문 이벤트의 JSON 역직렬화용 DTO.
 *
 * <p>Outbox 테이블의 payload 컬럼에 저장된 JSON 구조와 매핑된다.
 * 필드가 추가되어도 역직렬화가 깨지지 않도록 {@code @JsonIgnoreProperties}를 적용한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventPayload(
        String orderId,
        Long userId,
        Long restaurantId,
        String status,
        int totalAmount,
        List<OrderItemPayload> items,
        LocalDateTime createdAt
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItemPayload(
            Long menuId,
            String menuName,
            int quantity,
            int price
    ) {
    }
}
