package com.rushorder.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Service 설정.
 *
 * <p>Outbox 폴링 워커({@link com.rushorder.order.outbox.OutboxPublisher})가
 * {@code @Scheduled}를 사용하므로 스케줄링을 활성화한다.
 */
@Configuration
@EnableScheduling
public class OrderConfig {
}
