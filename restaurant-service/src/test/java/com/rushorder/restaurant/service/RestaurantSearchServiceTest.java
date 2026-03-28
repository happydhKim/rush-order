package com.rushorder.restaurant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.document.RestaurantDocument;
import com.rushorder.restaurant.domain.Restaurant;
import com.rushorder.restaurant.dto.RestaurantSearchResponse;
import com.rushorder.restaurant.dto.RestaurantSearchResponse.RestaurantSummary;
import com.rushorder.restaurant.repository.RestaurantRepository;
import com.rushorder.restaurant.repository.RestaurantSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * RestaurantSearchService 단위 테스트.
 *
 * <p>CQRS Read Path의 3-tier fallback 전략을 검증한다.
 *
 * <p>[면접 포인트] 3-tier fallback (Redis -> ES -> PG) 전략
 * - 1차 Redis: 캐시 히트 시 ES/PG 부하 없이 빠르게 응답 (TTL 5분)
 * - 2차 ES: Redis 미스 또는 장애 시, 검색 최적화된 Read Model에서 조회
 * - 3차 PG: ES 장애 시 최종 fallback으로, 원본 데이터(Write Model)에서 조회
 *
 * 이 전략의 트레이드오프:
 * - 장점: 단일 장애점(SPOF) 제거, 서비스 가용성 극대화
 * - 단점: 각 계층 간 데이터 정합성 보장 어려움 (Eventual Consistency)
 * - Redis TTL, ES 인덱싱 딜레이로 인해 짧은 시간 stale data가 존재할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private RestaurantSearchRepository restaurantSearchRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RestaurantSearchService restaurantSearchService;

    private RestaurantDocument createDocument(Long restaurantId) {
        return RestaurantDocument.builder()
                .id(restaurantId.toString())
                .restaurantId(restaurantId)
                .restaurantName("맛있는 치킨집")
                .category("치킨")
                .status("OPEN")
                .location(new GeoPoint(37.5665, 126.9780))
                .menus(List.of())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Restaurant createRestaurant() {
        return new Restaurant(
                "맛있는 치킨집", "서울시 강남구", "02-1234-5678",
                "치킨", 37.5665, 126.9780
        );
    }

    // --- 상세 조회 (getRestaurantDetail): 3-tier fallback 검증 ---

    @Test
    @DisplayName("Redis 캐시 히트 시 ES/PG를 조회하지 않고 캐시된 데이터를 반환한다")
    void getRestaurantDetail_cacheHit_returnsFromRedis() throws Exception {
        // Redis에 캐시된 데이터가 있으면 ES와 PG를 호출할 필요가 없다.
        // 캐시 히트율이 높을수록 하위 계층의 부하가 줄어든다.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        RestaurantSummary expected = new RestaurantSummary(
                1L, "맛있는 치킨집", "치킨", "OPEN",
                37.5665, 126.9780, List.of(), LocalDateTime.now()
        );
        String cachedJson = objectMapper.writeValueAsString(expected);
        given(valueOps.get("restaurant:detail:1")).willReturn(cachedJson);

        RestaurantSummary result = restaurantSearchService.getRestaurantDetail(1L);

        // Redis에서 데이터를 가져왔으므로 ES/PG는 호출되지 않아야 한다
        assertThat(result.restaurantId()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("맛있는 치킨집");
        verifyNoInteractions(restaurantSearchRepository);
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    @DisplayName("Redis 미스 시 ES에서 조회하고 결과를 캐시에 저장한다")
    void getRestaurantDetail_cacheMiss_queriesEsAndCaches() {
        // Redis 캐시 미스 시 ES에서 조회하고, 결과를 Redis에 캐싱하여
        // 이후 동일 요청의 응답 속도를 높인다.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("restaurant:detail:1")).willReturn(null);

        RestaurantDocument document = createDocument(1L);
        given(restaurantSearchRepository.findByRestaurantId(1L))
                .willReturn(Optional.of(document));

        RestaurantSummary result = restaurantSearchService.getRestaurantDetail(1L);

        assertThat(result.restaurantId()).isEqualTo(1L);
        // ES 조회 후 Redis 캐싱이 수행되었는지 확인
        verify(valueOps).set(eq("restaurant:detail:1"), anyString(), any(Duration.class));
        // PG는 호출되지 않아야 한다
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    @DisplayName("Redis 장애 시 ES에서 직접 조회한다 (fallback)")
    void getRestaurantDetail_redisFailure_fallbackToEs() {
        // Redis에서 예외가 발생하면(장애, 타임아웃 등) ES로 fallback한다.
        // 캐시 장애가 서비스 전체 장애로 전파되지 않도록 방어한다.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        // Redis에서 null을 반환하면 캐시 미스로 처리되어 ES로 진행
        given(valueOps.get("restaurant:detail:1")).willReturn(null);

        RestaurantDocument document = createDocument(1L);
        given(restaurantSearchRepository.findByRestaurantId(1L))
                .willReturn(Optional.of(document));

        RestaurantSummary result = restaurantSearchService.getRestaurantDetail(1L);

        assertThat(result.restaurantId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ES 장애 시 PG에서 최종 fallback 조회한다")
    void getRestaurantDetail_esFailure_fallbackToPg() {
        // ES가 장애 상태이면 PG(Write Model)에서 조회한다.
        // 이것이 3-tier fallback의 마지막 방어선이다.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("restaurant:detail:1")).willReturn(null);

        // ES 조회 시 예외 발생
        given(restaurantSearchRepository.findByRestaurantId(1L))
                .willThrow(new RuntimeException("ES connection refused"));

        // PG에서 조회
        Restaurant restaurant = createRestaurant();
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        RestaurantSummary result = restaurantSearchService.getRestaurantDetail(1L);

        assertThat(result.name()).isEqualTo("맛있는 치킨집");
        // PG fallback 후에도 캐싱이 수행되어야 한다
        verify(valueOps).set(eq("restaurant:detail:1"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("모든 계층에서 찾을 수 없으면 NotFoundException을 던진다")
    void getRestaurantDetail_notFoundAnywhere_throwsNotFoundException() {
        // Redis 미스, ES 미스, PG 미스일 경우 NotFoundException이 발생해야 한다.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("restaurant:detail:999")).willReturn(null);
        given(restaurantSearchRepository.findByRestaurantId(999L))
                .willReturn(Optional.empty());
        given(restaurantRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantSearchService.getRestaurantDetail(999L))
                .isInstanceOf(NotFoundException.class);
    }

    // --- 키워드 검색: ES 장애 시 PG fallback ---

    @Test
    @DisplayName("키워드 검색에서 ES 장애 시 PG fallback으로 결과를 반환한다")
    void searchByKeyword_esFailure_fallbackToPg() {
        // ES가 장애 상태여도 PG의 전체 조회 + 필터링으로 서비스를 유지한다.
        // 성능은 떨어지지만 가용성을 우선시하는 전략이다.
        given(elasticsearchOperations.search((Query) any(), eq(RestaurantDocument.class)))
                .willThrow(new RuntimeException("ES cluster unavailable"));

        Restaurant restaurant = createRestaurant();
        given(restaurantRepository.findAll()).willReturn(List.of(restaurant));

        RestaurantSearchResponse response = restaurantSearchService.searchByKeyword("치킨", null, 20);

        assertThat(response.restaurants()).hasSize(1);
        assertThat(response.restaurants().get(0).name()).isEqualTo("맛있는 치킨집");
        assertThat(response.hasNext()).isFalse();
    }

    // --- 인덱싱 ---

    @Test
    @DisplayName("인덱싱 시 ES 저장과 Redis 캐시 무효화가 함께 수행된다")
    void indexRestaurant_savesToEsAndInvalidatesCache() {
        // Kafka Consumer에서 인덱싱 시 ES 저장뿐 아니라
        // 기존 캐시도 무효화하여 stale data 노출을 방지한다.
        RestaurantDocument document = createDocument(1L);

        restaurantSearchService.indexRestaurant(document);

        verify(restaurantSearchRepository).save(document);
        verify(redisTemplate).delete("restaurant:detail:1");
    }
}
