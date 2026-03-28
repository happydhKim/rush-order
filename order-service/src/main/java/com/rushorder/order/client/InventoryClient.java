package com.rushorder.order.client;

import com.rushorder.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Inventory Service Feign Client.
 *
 * <p>주문 접수 시 재고 예약을 동기적으로 호출한다.
 * 재고 예약까지 동기 처리하는 이유: 초과판매를 방지하기 위해
 * 사용자에게 응답하기 전에 재고가 확보되었음을 보장해야 한다.
 *
 * <p>결제 이후 단계는 Outbox + Kafka를 통해 비동기로 처리한다.
 *
 * <p>Saga 흐름에서의 역할:
 * <ul>
 *   <li>reserve — 주문 접수 시 동기 호출 (이미 구현)</li>
 *   <li>confirm — 결제 성공 후 예약 확정</li>
 *   <li>release — 결제 실패 시 보상 트랜잭션으로 예약 해제</li>
 * </ul>
 */
@FeignClient(name = "inventory-service", url = "${services.inventory.url:http://localhost:8085}")
public interface InventoryClient {

    @PostMapping("/api/inventories/reserve")
    ApiResponse<Void> reserveStock(@RequestBody StockReserveCommand command);

    /**
     * 예약된 재고를 확정한다. 결제 성공 후 호출된다.
     * 예약 → 확정으로 전환하여 TTL 만료에 의한 자동 해제를 방지한다.
     */
    @PostMapping("/api/inventories/{menuId}/confirm")
    ApiResponse<Void> confirmStock(@PathVariable("menuId") Long menuId,
                                   @RequestParam("orderId") String orderId,
                                   @RequestParam("quantity") int quantity);

    /**
     * 예약된 재고를 해제한다. 보상 트랜잭션에서 호출된다.
     * 멱등성이 보장되어야 재시도 시 안전하다.
     */
    @PostMapping("/api/inventories/{menuId}/release")
    ApiResponse<Void> releaseStock(@PathVariable("menuId") Long menuId,
                                   @RequestParam("orderId") String orderId,
                                   @RequestParam("quantity") int quantity);

    /**
     * Feign 호출용 재고 예약 커맨드.
     * Inventory Service의 StockReserveRequest와 구조가 동일하지만,
     * 서비스 간 DTO 결합을 피하기 위해 별도 정의한다.
     *
     * <p>orderId는 Inventory Service에서 예약을 건별로 추적(TTL 5분)하기 위해 필요하다.
     */
    record StockReserveCommand(
            String orderId,
            java.util.List<StockItem> items
    ) {
        public record StockItem(Long menuId, int quantity) {
        }
    }
}
