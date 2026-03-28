package com.rushorder.order.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.order.client.InventoryClient;
import com.rushorder.order.domain.Order;
import com.rushorder.order.domain.OrderStatus;
import com.rushorder.order.dto.OrderResponse;
import com.rushorder.order.outbox.OutboxEvent;
import com.rushorder.order.outbox.OutboxRepository;
import com.rushorder.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Saga Orchestrator. 주문의 분산 트랜잭션 흐름을 관리한다.
 *
 * <p>전체 흐름:
 * <pre>
 *   1. startSaga()           — 주문 생성 후 호출, "payment-requested" Outbox 이벤트 저장
 *   2. [Outbox 폴링]         — Kafka로 결제 요청 발행
 *   3. [Payment Service]     — 결제 처리 후 "payment-result" 토픽 발행
 *   4. handlePaymentResult() — 결제 결과에 따라 확정 또는 보상
 * </pre>
 *
 * <p>[면접 포인트] Orchestration vs Choreography:
 * Choreography는 각 서비스가 이벤트를 발행/구독하여 암묵적으로 흐름을 만든다.
 * 서비스가 많아지면 전체 흐름을 추적하기 어렵고, 순환 의존이 생길 수 있다.
 * Orchestration은 Orchestrator가 중앙에서 흐름을 제어하므로 디버깅과 모니터링이 쉽다.
 * 단점은 Orchestrator가 SPOF가 될 수 있지만, 상태를 DB에 영속화하면 복구가 가능하다.
 *
 * <p>[면접 포인트] 동기/비동기 분리 전략:
 * 재고 예약은 동기(Feign)로 처리하여 초과판매를 방지하고,
 * 결제는 비동기(Outbox → Kafka)로 처리하여 응답 시간을 단축한다.
 * 재고는 "사용자에게 주문 접수 응답을 주기 전"에 확보해야 하지만,
 * 결제는 "접수 후 백그라운드"에서 처리해도 사용자 경험에 문제가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {

    private static final int MAX_COMPENSATION_RETRIES = 3;

    private final SagaInstanceRepository sagaInstanceRepository;
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Saga를 시작한다. 주문 생성 직후 같은 트랜잭션 내에서 호출된다.
     *
     * <p>SagaInstance를 생성하고, "payment-requested" Outbox 이벤트를 저장한다.
     * Outbox 폴링 워커가 이 이벤트를 Kafka로 발행하면 Payment Service가 소비한다.
     *
     * <p>같은 트랜잭션에서 Order + SagaInstance + OutboxEvent가 모두 저장되므로
     * 원자성이 보장된다. 하나라도 실패하면 전부 롤백된다.
     *
     * @param order 재고 예약까지 완료된 주문 (INVENTORY_RESERVED 상태)
     */
    @Transactional
    public void startSaga(Order order) {
        String sagaId = UUID.randomUUID().toString();
        String payload = serializeOrder(order);

        SagaInstance saga = new SagaInstance(sagaId, order.getOrderId(), payload);
        saga.startPayment();
        sagaInstanceRepository.save(saga);

        // Order 상태 전이: INVENTORY_RESERVED → PAYMENT_PROCESSING
        order.transitionTo(OrderStatus.PAYMENT_PROCESSING);

        // Outbox에 결제 요청 이벤트 저장 — 폴링 워커가 Kafka로 발행
        saveOutboxEvent(order, "payment-requested");

        log.info("Saga started: sagaId={}, orderId={}, status={}",
                sagaId, order.getOrderId(), saga.getStatus());
    }

    /**
     * 결제 결과를 처리한다. PaymentResultConsumer에서 호출된다.
     *
     * <p>멱등성: SagaInstance의 현재 상태를 확인하여 이미 처리된 결과는 무시한다.
     * Kafka의 at-least-once 특성상 동일 메시지가 재전달될 수 있기 때문이다.
     *
     * @param orderId          주문 ID
     * @param success          결제 성공 여부
     * @param pgTransactionId  PG 트랜잭션 ID (성공 시)
     */
    @Transactional
    public void handlePaymentResult(String orderId, boolean success, String pgTransactionId) {
        SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("SagaInstance", orderId));

        // 멱등성: 이미 완료/실패된 Saga는 재처리하지 않음
        if (saga.getStatus() == SagaStatus.COMPLETED
                || saga.getStatus() == SagaStatus.FAILED
                || saga.getStatus() == SagaStatus.COMPENSATION_FAILED) {
            log.warn("Saga already in terminal state: orderId={}, status={}",
                    orderId, saga.getStatus());
            return;
        }

        Order order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));

        if (success) {
            completeOrder(saga, order, pgTransactionId);
        } else {
            startCompensation(saga, order);
        }
    }

    /**
     * 결제 성공 시 주문을 확정한다.
     *
     * <p>재고 확정(confirm)을 Feign으로 동기 호출한다.
     * 이 시점에서 재고 확정 실패는 거의 발생하지 않지만(이미 예약됨),
     * 실패하면 예외가 전파되어 트랜잭션이 롤백되고 다음 Kafka 재전달에서 재시도된다.
     */
    private void completeOrder(SagaInstance saga, Order order, String pgTransactionId) {
        saga.completePayment();

        // 재고 확정 — 예약 상태에서 확정 상태로 전환
        confirmInventory(order);

        // Order 상태 전이: PAYMENT_PROCESSING → CONFIRMED
        order.transitionTo(OrderStatus.CONFIRMED);
        saga.complete();

        // "order-confirmed" 이벤트 발행 (Notification Service 등이 소비)
        saveOutboxEvent(order, "order-confirmed");

        log.info("Saga completed: orderId={}, pgTxId={}", order.getOrderId(), pgTransactionId);
    }

    /**
     * 보상 트랜잭션을 시작한다. 결제 실패 시 예약된 재고를 해제한다.
     *
     * <p>보상 실패 시 retryCount를 증가시키며, 최대 횟수 초과 시
     * COMPENSATION_FAILED 상태로 전환하고 DLQ 토픽에 발행한다.
     * 이후 수동 처리(운영팀 알림)가 필요하다.
     *
     * <p>[면접 포인트] 보상 트랜잭션의 멱등성:
     * Inventory Service의 release 엔드포인트가 멱등해야 한다.
     * 이미 해제된 재고에 대해 다시 release를 호출해도 에러가 나지 않아야
     * 재시도가 안전하다.
     */
    private void startCompensation(SagaInstance saga, Order order) {
        // Order 상태 전이: PAYMENT_PROCESSING → COMPENSATING
        order.transitionTo(OrderStatus.COMPENSATING);
        saga.startCompensation();

        try {
            releaseInventory(order);

            // 보상 성공 → 주문 취소
            order.transitionTo(OrderStatus.CANCELLED);
            saga.fail();
            saveOutboxEvent(order, "order-cancelled");

            log.info("Compensation completed: orderId={}", order.getOrderId());
        } catch (Exception e) {
            saga.incrementRetryCount();

            if (saga.getRetryCount() >= MAX_COMPENSATION_RETRIES) {
                // 최대 재시도 초과 → DLQ
                order.transitionTo(OrderStatus.COMPENSATION_FAILED);
                saga.failCompensation();
                sendToDlq(order, e);

                log.error("Compensation failed after {} retries, sent to DLQ: orderId={}",
                        MAX_COMPENSATION_RETRIES, order.getOrderId(), e);
            } else {
                log.warn("Compensation retry {}/{}: orderId={}, reason={}",
                        saga.getRetryCount(), MAX_COMPENSATION_RETRIES,
                        order.getOrderId(), e.getMessage());
            }
        }
    }

    /**
     * Inventory Service에 재고 확정을 요청한다.
     * 주문의 각 항목에 대해 개별 confirm 호출을 수행한다.
     */
    private void confirmInventory(Order order) {
        order.getItems().forEach(item ->
                inventoryClient.confirmStock(
                        item.getMenuId(), order.getOrderId(), item.getQuantity())
        );
    }

    /**
     * Inventory Service에 재고 해제를 요청한다.
     * 보상 트랜잭션의 일부로, 예약된 재고를 원복한다.
     */
    private void releaseInventory(Order order) {
        order.getItems().forEach(item ->
                inventoryClient.releaseStock(
                        item.getMenuId(), order.getOrderId(), item.getQuantity())
        );
    }

    private void saveOutboxEvent(Order order, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(OrderResponse.from(order));
            OutboxEvent event = new OutboxEvent("Order", order.getOrderId(), eventType, payload);
            outboxRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
    }

    private String serializeOrder(Order order) {
        try {
            return objectMapper.writeValueAsString(OrderResponse.from(order));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize order for saga payload", e);
        }
    }

    /**
     * 보상 실패 시 DLQ 토픽에 직접 발행한다.
     * Outbox를 경유하지 않고 직접 발행하는 이유: 이미 DB 트랜잭션이 커밋된 상태에서
     * 운영팀에 즉각 알림이 필요하며, DLQ 자체의 실패는 로그로 대체할 수 있다.
     */
    private void sendToDlq(Order order, Exception cause) {
        try {
            String payload = objectMapper.writeValueAsString(OrderResponse.from(order));
            kafkaTemplate.send("order-compensation-dlq", order.getOrderId(), payload);
        } catch (Exception e) {
            // DLQ 발행 실패는 로그로 남기고 무시 — 이미 DB에 COMPENSATION_FAILED로 저장됨
            log.error("Failed to send to DLQ: orderId={}", order.getOrderId(), e);
        }
    }
}
