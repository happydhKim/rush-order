package com.rushorder.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Inventory Service 공통 설정.
 *
 * <p>@EnableScheduling: 만료된 예약을 주기적으로 정리하는 스케줄러 활성화.
 * <p>@EnableRetry: 비관적 락 충돌 시 지수 백오프 재시도 활성화.
 */
@Configuration
@EnableScheduling
@EnableRetry
public class InventoryConfig {
}
