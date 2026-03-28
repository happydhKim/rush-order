package com.rushorder.restaurant.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Restaurant Service가 발행하는 Kafka 토픽을 명시적으로 생성한다.
 *
 * <p>CQRS Read Model 동기화 경로:
 * Restaurant(PG) → Outbox → Kafka(restaurant-updated) → Consumer → Elasticsearch
 *
 * <p>파티션 키 전략: key = restaurantId.
 * 같은 가게의 변경 이벤트가 같은 파티션에 들어가 순서가 보장된다.
 * ES 인덱싱 시 최신 상태가 이전 상태를 덮어쓰므로, 순서 보장이 정합성의 핵심이다.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITION_COUNT = 3;
    private static final int REPLICA_COUNT = 1;

    /**
     * 가게/메뉴 변경 이벤트. Outbox 워커가 발행하며, 같은 서비스의 Consumer가
     * 수신하여 Elasticsearch Read Model을 동기화한다.
     */
    @Bean
    public NewTopic restaurantUpdatedTopic() {
        return TopicBuilder.name("restaurant-updated")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }
}
