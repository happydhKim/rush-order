package com.rushorder.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Gateway Rate Limiting 키 리졸버 설정.
 *
 * <p>Spring Cloud Gateway의 내장 {@code RequestRateLimiter}는
 * Redis 기반 Token Bucket 알고리즘을 사용한다.
 * Token Bucket을 선택한 이유: 일정한 평균 속도를 유지하면서도
 * 짧은 버스트를 허용하여 실제 트래픽 패턴에 적합하다.
 *
 * <p>키 리졸버가 요청마다 "누구의 요청인지"를 결정하며,
 * 이 키 단위로 토큰 버킷이 생성된다.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * IP 기반 키 리졸버.
     *
     * <p>인증 전 단계에서도 Rate Limiting이 동작해야 하므로
     * IP 주소를 기본 키로 사용한다.
     * X-Forwarded-For 헤더가 있으면 프록시 뒤의 실제 클라이언트 IP를 사용하고,
     * 없으면 직접 연결된 RemoteAddress를 사용한다.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // X-Forwarded-For의 첫 번째 IP가 원본 클라이언트
                return Mono.just(forwardedFor.split(",")[0].trim());
            }
            return Mono.just(
                    Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                            .getAddress().getHostAddress()
            );
        };
    }

    /**
     * 사용자별 키 리졸버.
     *
     * <p>JWT에서 추출한 사용자 ID를 키로 사용한다.
     * 인증되지 않은 요청은 "anonymous"로 폴백하여
     * 모든 미인증 요청이 하나의 버킷을 공유한다.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> principal.getName())
                .defaultIfEmpty("anonymous");
    }
}
