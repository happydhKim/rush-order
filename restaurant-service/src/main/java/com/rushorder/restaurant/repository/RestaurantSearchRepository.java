package com.rushorder.restaurant.repository;

import com.rushorder.restaurant.document.RestaurantDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.Optional;

/**
 * Elasticsearch CRUD 레포지토리.
 *
 * <p>Spring Data Elasticsearch가 제공하는 기본 CRUD 메서드를 사용한다.
 * 복잡한 검색 쿼리는 {@link com.rushorder.restaurant.service.RestaurantSearchService}에서
 * ElasticsearchOperations를 직접 사용한다.
 */
public interface RestaurantSearchRepository extends ElasticsearchRepository<RestaurantDocument, String> {

    Optional<RestaurantDocument> findByRestaurantId(Long restaurantId);
}
