package com.rushorder.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 재고 예약 요청.
 *
 * <p>하나의 주문에 여러 메뉴가 포함될 수 있으므로 리스트로 수신한다.
 * 데드락 방지를 위해 서비스 내부에서 menuId 오름차순으로 정렬 후 처리한다.
 *
 * <p>orderId는 StockReservation 엔티티에서 예약을 건별로 추적하기 위해 필요하다.
 * 이를 통해 TTL 만료 시 어떤 주문의 예약인지 식별할 수 있다.
 */
public record StockReserveRequest(
        @NotBlank(message = "Order ID must not be blank")
        String orderId,

        @NotEmpty(message = "Items must not be empty")
        @Valid
        List<StockItem> items
) {

    public record StockItem(
            Long menuId,
            int quantity
    ) {
    }
}
