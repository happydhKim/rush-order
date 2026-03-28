package com.rushorder.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Order Service가 발행/소비하는 Kafka 토픽을 명시적으로 생성한다.
 *
 * <p>프로덕션에서는 auto.create.topics.enable=false로 설정하고,
 * 이 Configuration이 애플리케이션 기동 시 KafkaAdmin을 통해 토픽을 생성한다.
 * 이미 존재하는 토픽은 무시된다.
 *
 * <p>파티션 수 3의 근거:
 * <ul>
 *   <li>단일 브로커 환경에서 Consumer 병렬 처리의 기본 단위</li>
 *   <li>같은 orderId를 키로 사용하므로, 동일 주문의 이벤트는 같은 파티션에 들어가 순서가 보장된다</li>
 *   <li>Consumer Group 내 최대 3개의 Consumer가 병렬로 처리할 수 있다</li>
 * </ul>
 *
 * <p>파티션 키 전략:
 * <ul>
 *   <li>order-created, payment-requested, order-confirmed, order-cancelled: key = orderId</li>
 *   <li>payment-result: key = orderId (동일 주문의 결제 결과가 순서대로 도착해야 하므로)</li>
 * </ul>
 *
 * <p>replicas=1인 이유: 단일 브로커 개발 환경. 프로덕션에서는 최소 3으로 설정해야 한다.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITION_COUNT = 3;
    private static final int REPLICA_COUNT = 1;

    /**
     * 주문 생성 이벤트. Outbox 폴링 워커가 발행하며, Notification Service가 소비한다.
     */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name("order-created")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }

    /**
     * 결제 요청 이벤트. Saga Orchestrator가 발행하며, Payment Service가 소비한다.
     */
    @Bean
    public NewTopic paymentRequestedTopic() {
        return TopicBuilder.name("payment-requested")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }

    /**
     * 결제 결과 이벤트. Payment Service가 발행하며, Order Service(Saga)가 소비한다.
     *
     * <p>결제 성공/실패 모두 이 토픽으로 발행된다.
     * Saga Orchestrator는 결과에 따라 주문 확정 또는 보상 트랜잭션을 수행한다.
     */
    @Bean
    public NewTopic paymentResultTopic() {
        return TopicBuilder.name("payment-result")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }

    /**
     * 주문 확정 이벤트. Saga 완료 후 발행되며, Notification Service가 소비한다.
     */
    @Bean
    public NewTopic orderConfirmedTopic() {
        return TopicBuilder.name("order-confirmed")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }

    /**
     * 주문 취소 이벤트. 보상 트랜잭션 완료 후 발행되며, Notification Service가 소비한다.
     */
    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name("order-cancelled")
                .partitions(PARTITION_COUNT)
                .replicas(REPLICA_COUNT)
                .build();
    }
}
