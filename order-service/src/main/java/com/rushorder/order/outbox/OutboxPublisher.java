package com.rushorder.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 폴링 워커.
 *
 * <p>1초 간격으로 미발행 이벤트를 조회하여 Kafka로 발행한다.
 * 발행 후 processed=true로 업데이트한다.
 *
 * <p>장애 시나리오:
 * <ul>
 *   <li>Kafka 발행 성공 + DB 업데이트 실패 → 재시작 시 중복 발행 (At-least-once)</li>
 *   <li>Kafka 발행 실패 → 예외 발생, processed는 false 유지, 다음 폴링에서 재시도</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository
                .findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : events) {
            try {
                // aggregateId를 Kafka 파티션 키로 사용하여 같은 주문의 이벤트 순서를 보장
                kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload());
                event.markAsProcessed();
                log.debug("Published outbox event: type={}, aggregateId={}",
                        event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, type={}",
                        event.getId(), event.getEventType(), e);
                // 실패한 이벤트는 다음 폴링 주기에서 재시도
                break;
            }
        }
    }
}
