package com.rushorder.restaurant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch 설정.
 *
 * <p>JpaConfig에서 JPA 레포지토리와 ES 레포지토리의 베이스 패키지를 분리해야 한다.
 * JPA는 repository 패키지의 JpaRepository 구현체만 스캔하고,
 * ES는 같은 패키지의 ElasticsearchRepository 구현체를 스캔한다.
 *
 * <p>Spring Data는 인터페이스 타입(JpaRepository vs ElasticsearchRepository)으로
 * 자동 구분하므로 같은 패키지에 있어도 충돌하지 않는다.
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.rushorder.restaurant.repository")
public class ElasticsearchConfig {
}
