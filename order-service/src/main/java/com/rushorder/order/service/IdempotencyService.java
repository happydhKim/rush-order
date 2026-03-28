package com.rushorder.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis 기반 멱등성 서비스.
 *
 * <p>{@code SET NX}(Set if Not eXists)를 사용하여 멱등키의 중복 여부를
 * 원자적으로 확인하고 선점한다. 단일 명령어이므로 "확인 → 저장" 사이의
 * 경합(Race Condition)이 발생하지 않는다.
 *
 * <p>Redis 장애 시에는 DB unique constraint(idempotencyKey 컬럼)가
 * 최후의 안전장치로 동작한다. 성능은 저하되지만 서비스는 유지된다.
 *
 * @see com.rushorder.order.domain.Order#idempotencyKey
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    // 24시간: 사용자가 하루 안에 재시도할 가능성을 커버
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 멱등키가 이미 처리되었는지 확인한다.
     *
     * <p>SET NX는 원자적 연산이므로 동시에 같은 키로 요청이 와도
     * 하나만 성공(false 반환)하고 나머지는 중복(true 반환)으로 판단된다.
     *
     * @param idempotencyKey 클라이언트가 생성한 UUID
     * @return true면 이미 처리된 중복 요청, false면 신규 요청
     */
    public boolean isDuplicate(String idempotencyKey) {
        try {
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + idempotencyKey, "PROCESSING", TTL);
            return result == null || !result;
        } catch (Exception e) {
            // Redis 장애 시 중복 체크를 건너뛰고 DB unique constraint에 위임
            log.warn("Redis unavailable for idempotency check, falling back to DB: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 처리 완료 후 응답을 캐싱한다.
     * 동일 키로 재요청 시 캐싱된 응답을 즉시 반환할 수 있다.
     */
    public void cacheResponse(String idempotencyKey, String response) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, response, TTL);
        } catch (Exception e) {
            log.warn("Failed to cache idempotency response: {}", e.getMessage());
        }
    }

    public String getCachedResponse(String idempotencyKey) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("Failed to get cached idempotency response: {}", e.getMessage());
            return null;
        }
    }
}
