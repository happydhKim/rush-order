package com.rushorder.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * IdempotencyService 단위 테스트.
 *
 * <p>Redis SET NX 기반 멱등성 서비스의 동작을 검증한다.
 * 동시성 환경에서 중복 요청을 원자적으로 감지하는 것이 핵심이다.
 *
 * <p>[면접 포인트] SET NX 원자성이 TOCTOU(Time of Check to Time of Use)를 방지하는 이유:
 * "키 존재 확인(GET)" + "키 저장(SET)"을 2단계로 수행하면,
 * 두 요청이 동시에 GET → 둘 다 "없음" → 둘 다 SET하는 Race Condition이 발생한다.
 * SET NX는 "확인 + 저장"을 단일 원자적 명령어로 수행하므로
 * 동시 요청 중 정확히 하나만 성공(true)하고 나머지는 실패(false)한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService 단위 테스트")
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private IdempotencyService idempotencyService;

    private static final String TEST_KEY = "test-uuid-key";
    private static final String REDIS_KEY = "idempotency:" + TEST_KEY;

    @Nested
    @DisplayName("isDuplicate - 중복 확인")
    class IsDuplicate {

        @Test
        @DisplayName("새로운 키 (SET NX 성공) -> isDuplicate false 반환")
        void isDuplicate_newKey_returnsFalse() {
            // SET NX가 true를 반환하면 해당 키가 처음 사용된 것이므로
            // 중복이 아니다(isDuplicate = false).
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("PROCESSING"), any(Duration.class)))
                    .willReturn(true);

            boolean result = idempotencyService.isDuplicate(TEST_KEY);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("중복 키 (SET NX 실패) -> isDuplicate true 반환")
        void isDuplicate_existingKey_returnsTrue() {
            // SET NX가 false를 반환하면 이미 다른 요청이 해당 키를 선점한 것이므로
            // 중복 요청이다(isDuplicate = true).
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("PROCESSING"), any(Duration.class)))
                    .willReturn(false);

            boolean result = idempotencyService.isDuplicate(TEST_KEY);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("SET NX 결과가 null -> isDuplicate true 반환 (안전한 쪽으로)")
        void isDuplicate_nullResult_returnsTrue() {
            // Redis 클라이언트가 null을 반환하는 경우(네트워크 이슈 등)
            // 안전한 쪽(중복으로 판단)으로 처리하여 중복 주문을 방지한다.
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("PROCESSING"), any(Duration.class)))
                    .willReturn(null);

            boolean result = idempotencyService.isDuplicate(TEST_KEY);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Redis 장애 시 isDuplicate false 반환 -> DB unique constraint에 위임")
        void isDuplicate_redisFailure_fallsBackToFalse() {
            // [면접 포인트] Redis 장애 시 폴백 전략:
            // Redis가 다운되면 멱등키 확인을 건너뛰고 DB unique constraint가
            // 최후의 안전장치로 동작한다. 성능은 저하되지만 서비스는 유지된다.
            // false를 반환하는 이유: Redis 장애로 인해 정상 요청을 거부하면
            // 가용성이 떨어지므로, 중복 방지는 DB에 위임하고 요청은 통과시킨다.
            given(redisTemplate.opsForValue()).willThrow(new RuntimeException("Redis connection refused"));

            boolean result = idempotencyService.isDuplicate(TEST_KEY);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("cacheResponse - 응답 캐싱")
    class CacheResponse {

        @Test
        @DisplayName("처리 완료 후 응답을 Redis에 캐싱한다")
        void cacheResponse_savesToRedis() {
            // 동일 멱등키로 재요청 시 캐싱된 응답을 즉시 반환할 수 있도록
            // 처리 완료 후 응답 JSON을 Redis에 저장한다.
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            String response = "{\"orderId\":\"order-1\",\"status\":\"CONFIRMED\"}";

            idempotencyService.cacheResponse(TEST_KEY, response);

            then(valueOperations).should().set(eq(REDIS_KEY), eq(response), any(Duration.class));
        }

        @Test
        @DisplayName("Redis 장애 시 캐싱 실패를 무시한다 (서비스 중단 방지)")
        void cacheResponse_redisFailure_silentlyIgnored() {
            // 응답 캐싱 실패는 치명적이지 않다. 재요청 시 DB에서 조회하면 된다.
            // 예외를 전파하면 이미 성공한 주문 처리까지 롤백될 수 있으므로 무시한다.
            given(redisTemplate.opsForValue()).willThrow(new RuntimeException("Redis timeout"));

            // 예외가 전파되지 않아야 한다
            idempotencyService.cacheResponse(TEST_KEY, "response");
        }
    }

    @Nested
    @DisplayName("getCachedResponse - 캐싱된 응답 조회")
    class GetCachedResponse {

        @Test
        @DisplayName("캐싱된 응답이 있으면 반환한다")
        void getCachedResponse_exists_returnsResponse() {
            // 멱등키에 대한 이전 처리 결과가 캐싱되어 있으면 즉시 반환한다
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            String cachedResponse = "{\"orderId\":\"order-1\"}";
            given(valueOperations.get(REDIS_KEY)).willReturn(cachedResponse);

            String result = idempotencyService.getCachedResponse(TEST_KEY);

            assertThat(result).isEqualTo(cachedResponse);
        }

        @Test
        @DisplayName("캐싱된 응답이 없으면 null을 반환한다")
        void getCachedResponse_notExists_returnsNull() {
            // 아직 처리가 완료되지 않았거나 TTL이 만료된 경우 null을 반환한다
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(REDIS_KEY)).willReturn(null);

            String result = idempotencyService.getCachedResponse(TEST_KEY);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Redis 장애 시 null을 반환한다")
        void getCachedResponse_redisFailure_returnsNull() {
            // Redis 장애 시 null을 반환하여 호출자가 DB에서 조회하도록 유도한다
            given(redisTemplate.opsForValue()).willThrow(new RuntimeException("Redis connection refused"));

            String result = idempotencyService.getCachedResponse(TEST_KEY);

            assertThat(result).isNull();
        }
    }
}
