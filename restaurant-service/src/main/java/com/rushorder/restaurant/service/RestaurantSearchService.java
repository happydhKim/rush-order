package com.rushorder.restaurant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.document.RestaurantDocument;
import com.rushorder.restaurant.domain.Restaurant;
import com.rushorder.restaurant.dto.RestaurantSearchResponse;
import com.rushorder.restaurant.dto.RestaurantSearchResponse.RestaurantSummary;
import com.rushorder.restaurant.repository.RestaurantRepository;
import com.rushorder.restaurant.repository.RestaurantSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;

/**
 * CQRS Read Path 검색 서비스.
 *
 * <p>검색 요청을 Elasticsearch로 처리하되, 장애 시 PostgreSQL로 fallback한다.
 * 상세 조회는 Redis 캐시를 먼저 확인하여 ES/PG 부하를 줄인다.
 *
 * <p>키셋 페이징(cursor-based pagination)을 사용하여
 * offset 방식의 "deep pagination" 성능 문제를 회피한다.
 * cursor는 updatedAt 필드의 ISO-8601 문자열이다.
 *
 * @see RestaurantSearchRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantSearchService {

    private static final String CACHE_PREFIX = "restaurant:detail:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final DateTimeFormatter CURSOR_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ElasticsearchOperations elasticsearchOperations;
    private final RestaurantSearchRepository restaurantSearchRepository;
    private final RestaurantRepository restaurantRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Nori 분석기 기반 키워드 검색.
     *
     * <p>가게명과 메뉴명을 동시에 검색한다. 메뉴명은 nested query로 검색하며,
     * 가게명 매치 또는 메뉴명 매치 중 하나라도 만족하면 결과에 포함된다.
     *
     * @param keyword 검색 키워드
     * @param cursor  이전 페이지의 마지막 updatedAt (null이면 첫 페이지)
     * @param size    페이지 크기
     * @return 검색 결과 + 다음 커서
     */
    public RestaurantSearchResponse searchByKeyword(String keyword, String cursor, int size) {
        try {
            NativeQuery query = buildKeywordQuery(keyword, cursor, size);
            return executeSearch(query, size);
        } catch (Exception e) {
            log.warn("ES 키워드 검색 실패, PG fallback 수행. keyword={}", keyword, e);
            return fallbackKeywordSearch(keyword, size);
        }
    }

    /**
     * 카테고리 기반 필터 검색.
     *
     * @param category 카테고리 (keyword 타입이므로 정확 일치)
     * @param cursor   커서
     * @param size     페이지 크기
     * @return 검색 결과
     */
    public RestaurantSearchResponse searchByCategory(String category, String cursor, int size) {
        try {
            NativeQuery query = buildCategoryQuery(category, cursor, size);
            return executeSearch(query, size);
        } catch (Exception e) {
            log.warn("ES 카테고리 검색 실패, PG fallback 수행. category={}", category, e);
            return fallbackCategorySearch(category, size);
        }
    }

    /**
     * 위치 기반 근처 가게 검색.
     *
     * <p>geo_distance 쿼리를 사용하여 지정 반경 내의 가게를 검색한다.
     *
     * @param lat        위도
     * @param lon        경도
     * @param distanceKm 검색 반경 (km)
     * @param cursor     커서
     * @param size       페이지 크기
     * @return 검색 결과
     */
    public RestaurantSearchResponse searchNearby(double lat, double lon, double distanceKm,
                                                  String cursor, int size) {
        try {
            NativeQuery query = buildNearbyQuery(lat, lon, distanceKm, cursor, size);
            return executeSearch(query, size);
        } catch (Exception e) {
            log.warn("ES 위치 검색 실패, PG fallback은 미지원. lat={}, lon={}", lat, lon, e);
            // 위치 기반 검색은 PG fallback이 비효율적이므로 빈 결과 반환
            return new RestaurantSearchResponse(List.of(), null, false);
        }
    }

    /**
     * 가게 상세 조회. Redis 캐시 -> ES -> PG 순서로 fallback한다.
     *
     * <p>캐시 히트율을 높여 ES/PG 부하를 줄이는 것이 목적이다.
     * TTL 5분으로 설정하여 데이터 신선도와 성능의 균형을 맞춘다.
     *
     * @param restaurantId 가게 ID
     * @return 가게 상세 정보
     * @throws NotFoundException 어디에서도 찾을 수 없는 경우
     */
    public RestaurantSummary getRestaurantDetail(Long restaurantId) {
        // 1차: Redis 캐시
        String cacheKey = CACHE_PREFIX + restaurantId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, RestaurantSummary.class);
            } catch (JsonProcessingException e) {
                log.warn("캐시 역직렬화 실패, ES 조회로 fallback. restaurantId={}", restaurantId, e);
            }
        }

        // 2차: Elasticsearch
        try {
            RestaurantDocument document = restaurantSearchRepository.findByRestaurantId(restaurantId)
                    .orElse(null);
            if (document != null) {
                RestaurantSummary summary = RestaurantSummary.from(document);
                cacheResult(cacheKey, summary);
                return summary;
            }
        } catch (Exception e) {
            log.warn("ES 상세 조회 실패, PG fallback 수행. restaurantId={}", restaurantId, e);
        }

        // 3차: PostgreSQL (최종 fallback)
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant", restaurantId.toString()));
        RestaurantDocument document = RestaurantDocument.from(restaurant);
        RestaurantSummary summary = RestaurantSummary.from(document);
        cacheResult(cacheKey, summary);
        return summary;
    }

    /**
     * ES 문서를 인덱싱한다. Kafka Consumer에서 호출된다.
     *
     * @param document ES 문서
     */
    public void indexRestaurant(RestaurantDocument document) {
        restaurantSearchRepository.save(document);
        // 캐시 무효화: 기존 캐시가 있으면 삭제하여 최신 데이터를 반영
        String cacheKey = CACHE_PREFIX + document.getRestaurantId();
        redisTemplate.delete(cacheKey);
        log.info("가게 인덱싱 완료 및 캐시 무효화. restaurantId={}", document.getRestaurantId());
    }

    // --- Private: 쿼리 빌더 ---

    private NativeQuery buildKeywordQuery(String keyword, String cursor, int size) {
        var nameQuery = QueryBuilders.match(m -> m
                .field("restaurantName")
                .query(keyword));

        var menuNameQuery = QueryBuilders.nested(n -> n
                .path("menus")
                .query(q -> q.match(m -> m
                        .field("menus.menuName")
                        .query(keyword))));

        var boolQuery = QueryBuilders.bool(b -> b
                .should(nameQuery)
                .should(menuNameQuery)
                .minimumShouldMatch("1"));

        var builder = NativeQuery.builder()
                .withQuery(q -> q.bool(boolQuery.bool()))
                .withSort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                .withPageable(PageRequest.of(0, size + 1));

        if (cursor != null && !cursor.isBlank()) {
            LocalDateTime cursorTime = LocalDateTime.parse(cursor, CURSOR_FORMAT);
            String cursorValue = cursorTime.format(CURSOR_FORMAT);
            var filteredBool = QueryBuilders.bool(b -> b
                    .must(boolQuery)
                    .filter(f -> f.range(r -> r
                            .date(d -> d.field("updatedAt").lt(cursorValue)))));
            builder.withQuery(q -> q.bool(filteredBool.bool()));
        }

        return builder.build();
    }

    private NativeQuery buildCategoryQuery(String category, String cursor, int size) {
        var termQuery = QueryBuilders.term(t -> t
                .field("category")
                .value(category));

        var builder = NativeQuery.builder()
                .withQuery(q -> q.term(termQuery.term()))
                .withSort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                .withPageable(PageRequest.of(0, size + 1));

        if (cursor != null && !cursor.isBlank()) {
            var filteredBool = QueryBuilders.bool(b -> b
                    .must(termQuery)
                    .filter(f -> f.range(r -> r
                            .date(d -> d.field("updatedAt").lt(cursor)))));
            builder.withQuery(q -> q.bool(filteredBool.bool()));
        }

        return builder.build();
    }

    private NativeQuery buildNearbyQuery(double lat, double lon, double distanceKm,
                                          String cursor, int size) {
        var geoQuery = QueryBuilders.geoDistance(g -> g
                .field("location")
                .location(l -> l.latlon(ll -> ll.lat(lat).lon(lon)))
                .distance(distanceKm + "km"));

        var builder = NativeQuery.builder()
                .withQuery(q -> q.geoDistance(geoQuery.geoDistance()))
                .withSort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                .withPageable(PageRequest.of(0, size + 1));

        if (cursor != null && !cursor.isBlank()) {
            var filteredBool = QueryBuilders.bool(b -> b
                    .must(geoQuery)
                    .filter(f -> f.range(r -> r
                            .date(d -> d.field("updatedAt").lt(cursor)))));
            builder.withQuery(q -> q.bool(filteredBool.bool()));
        }

        return builder.build();
    }

    // --- Private: 공통 유틸 ---

    private RestaurantSearchResponse executeSearch(NativeQuery query, int size) {
        SearchHits<RestaurantDocument> searchHits = elasticsearchOperations.search(
                query, RestaurantDocument.class);

        List<RestaurantDocument> documents = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();

        // size+1개를 조회하여 다음 페이지 존재 여부 판단
        boolean hasNext = documents.size() > size;
        List<RestaurantDocument> resultDocs = hasNext
                ? documents.subList(0, size)
                : documents;

        List<RestaurantSummary> summaries = resultDocs.stream()
                .map(RestaurantSummary::from)
                .toList();

        String nextCursor = hasNext && !resultDocs.isEmpty()
                ? resultDocs.getLast().getUpdatedAt().format(CURSOR_FORMAT)
                : null;

        return new RestaurantSearchResponse(summaries, nextCursor, hasNext);
    }

    private void cacheResult(String cacheKey, RestaurantSummary summary) {
        try {
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("캐시 직렬화 실패. cacheKey={}", cacheKey, e);
        }
    }

    /**
     * ES 장애 시 PG ILIKE로 키워드 검색을 대체한다.
     * 성능은 떨어지지만 서비스 가용성을 유지한다.
     */
    private RestaurantSearchResponse fallbackKeywordSearch(String keyword, int size) {
        // PG ILIKE 검색은 RestaurantRepository에 별도 쿼리 메서드가 필요하므로
        // 현재는 전체 조회 후 필터링으로 대체
        List<Restaurant> restaurants = restaurantRepository.findAll().stream()
                .filter(r -> r.getName().contains(keyword) ||
                        r.getMenus().stream().anyMatch(m -> m.getName().contains(keyword)))
                .limit(size)
                .toList();

        List<RestaurantSummary> summaries = restaurants.stream()
                .map(RestaurantDocument::from)
                .map(RestaurantSummary::from)
                .toList();

        return new RestaurantSearchResponse(summaries, null, false);
    }

    private RestaurantSearchResponse fallbackCategorySearch(String category, int size) {
        List<Restaurant> restaurants = restaurantRepository.findAll().stream()
                .filter(r -> r.getCategory().equalsIgnoreCase(category))
                .limit(size)
                .toList();

        List<RestaurantSummary> summaries = restaurants.stream()
                .map(RestaurantDocument::from)
                .map(RestaurantSummary::from)
                .toList();

        return new RestaurantSearchResponse(summaries, null, false);
    }
}
