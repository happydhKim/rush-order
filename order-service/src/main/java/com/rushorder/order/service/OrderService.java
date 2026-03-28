package com.rushorder.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.DuplicateRequestException;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.order.client.InventoryClient;
import com.rushorder.order.client.InventoryClient.StockReserveCommand;
import com.rushorder.order.client.InventoryClient.StockReserveCommand.StockItem;
import com.rushorder.order.domain.Order;
import com.rushorder.order.domain.OrderItem;
import com.rushorder.order.domain.OrderStatus;
import com.rushorder.order.dto.OrderCreateRequest;
import com.rushorder.order.dto.OrderCreateRequest.OrderItemRequest;
import com.rushorder.order.dto.OrderResponse;
import com.rushorder.order.outbox.OutboxEvent;
import com.rushorder.order.outbox.OutboxRepository;
import com.rushorder.order.repository.OrderRepository;
import com.rushorder.order.saga.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 주문 처리 서비스.
 *
 * <p>주문 생성의 핵심 플로우:
 * <ol>
 *   <li>멱등키 중복 확인 (Redis SET NX)</li>
 *   <li>재고 예약 (Inventory Service, 동기 Feign 호출)</li>
 *   <li>주문 저장 + Outbox 이벤트 저장 (단일 DB 트랜잭션)</li>
 *   <li>Saga 시작 — 결제 요청 이벤트 Outbox 저장</li>
 *   <li>PAYMENT_PROCESSING 응답 반환</li>
 * </ol>
 *
 * <p>재고 예약을 동기로 처리하는 이유:
 * 사용자에게 "주문 접수" 응답을 주기 전에 재고가 확보되었음을 보장해야
 * 초과판매를 방지할 수 있다. 결제 이후 단계는 Outbox → Kafka로 비동기 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final IdempotencyService idempotencyService;
    private final InventoryClient inventoryClient;
    private final SagaOrchestrator sagaOrchestrator;
    private final ObjectMapper objectMapper;

    /**
     * 주문을 생성한다.
     *
     * <p>하나의 트랜잭션에서 Order INSERT + Outbox INSERT를 수행하여
     * Dual Write 문제를 방지한다. Kafka 발행은 Outbox 폴링 워커가
     * 비동기로 처리한다.
     *
     * @param request        주문 생성 요청
     * @param idempotencyKey 클라이언트가 생성한 멱등키 (X-Idempotency-Key 헤더)
     * @return 주문 응답 (PENDING 상태)
     * @throws DuplicateRequestException 이미 처리된 멱등키인 경우
     */
    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request, String idempotencyKey) {
        // 1. 멱등키 중복 확인
        if (idempotencyService.isDuplicate(idempotencyKey)) {
            throw new DuplicateRequestException(idempotencyKey);
        }

        // 2. orderId를 먼저 생성 — Inventory Service에서 예약 TTL 추적에 사용
        String orderId = UUID.randomUUID().toString();

        // 3. 재고 예약 (동기 호출 - 초과판매 방지)
        reserveInventory(orderId, request);
        Order order = new Order(orderId, request.userId(), request.restaurantId(), idempotencyKey);

        for (OrderItemRequest itemReq : request.items()) {
            order.addItem(new OrderItem(
                    itemReq.menuId(),
                    itemReq.menuName(),
                    itemReq.price(),
                    itemReq.quantity()
            ));
        }

        order.transitionTo(OrderStatus.INVENTORY_RESERVED);

        // 4. 주문 저장 + Outbox 이벤트 (단일 트랜잭션)
        orderRepository.save(order);
        saveOutboxEvent(order);

        // 5. Saga 시작 — 같은 트랜잭션에서 SagaInstance + 결제 요청 Outbox 저장
        sagaOrchestrator.startSaga(order);

        log.info("Order created: orderId={}, status={}, totalAmount={}",
                orderId, order.getStatus(), order.getTotalAmount());

        return OrderResponse.from(order);
    }

    public OrderResponse getOrder(String orderId) {
        Order order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return OrderResponse.from(order);
    }

    /**
     * Inventory Service에 재고 예약을 동기 요청한다.
     * 재고 부족 시 FeignErrorDecoder가 BusinessException으로 변환하여 전파하며,
     * 트랜잭션이 롤백된다.
     *
     * @param orderId Inventory Service에서 예약 TTL 추적에 사용
     */
    private void reserveInventory(String orderId, OrderCreateRequest request) {
        var stockItems = request.items().stream()
                .map(item -> new StockItem(item.menuId(), item.quantity()))
                .toList();

        inventoryClient.reserveStock(new StockReserveCommand(orderId, stockItems));
    }

    /**
     * Outbox 이벤트를 저장한다.
     * 주문 엔티티와 같은 트랜잭션에서 실행되어 원자성을 보장한다.
     */
    private void saveOutboxEvent(Order order) {
        try {
            String payload = objectMapper.writeValueAsString(OrderResponse.from(order));
            OutboxEvent event = new OutboxEvent(
                    "Order",
                    order.getOrderId(),
                    "order-created",
                    payload
            );
            outboxRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize order event", e);
        }
    }
}
