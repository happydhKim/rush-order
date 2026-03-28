package com.rushorder.restaurant.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.restaurant.document.RestaurantDocument;
import com.rushorder.restaurant.service.RestaurantSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka를 통해 가게 변경 이벤트를 수신하여 ES에 인덱싱하는 컨슈머.
 *
 * <p>Write Path(PostgreSQL) → Outbox → Kafka → 이 컨슈머 → Elasticsearch
 * 경로를 통해 Read Model을 비동기 동기화한다.
 *
 * <p>이벤트 페이로드는 RestaurantDocument의 JSON 직렬화 형태이다.
 * 역직렬화 실패 시 로그만 남기고 소비를 계속한다 (DLQ 연동은 추후 구현).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantEventConsumer {

    private final ObjectMapper objectMapper;
    private final RestaurantSearchService restaurantSearchService;

    /**
     * restaurant-updated 토픽의 이벤트를 소비하여 ES에 인덱싱한다.
     *
     * <p>메시지 처리 실패 시 예외를 삼켜서 offset commit이 정상 진행되도록 한다.
     * 실패한 메시지는 로그로 추적하며, DLQ(Dead Letter Queue) 전략은 추후 추가한다.
     *
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(topics = "restaurant-updated", groupId = "restaurant-service")
    public void consumeRestaurantEvent(String message) {
        log.info("가게 변경 이벤트 수신. payload 길이={}", message.length());

        try {
            RestaurantDocument document = objectMapper.readValue(message, RestaurantDocument.class);
            restaurantSearchService.indexRestaurant(document);
            log.info("ES 인덱싱 성공. restaurantId={}", document.getRestaurantId());
        } catch (Exception e) {
            // NOTE: 여기서 예외를 throw하면 Kafka consumer가 재시도 루프에 빠질 수 있다.
            // 실패 메시지는 로그로 추적하고, DLQ 전략 도입 시 개선한다.
            log.error("가게 변경 이벤트 처리 실패. message={}", message, e);
        }
    }
}
