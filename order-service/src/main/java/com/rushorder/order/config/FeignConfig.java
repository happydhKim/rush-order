package com.rushorder.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 글로벌 설정.
 *
 * <p>커스텀 {@link FeignErrorDecoder}를 등록하여 다운스트림 서비스의
 * HTTP 에러 응답을 도메인 예외로 변환한다.
 */
@Configuration
public class FeignConfig {

    @Bean
    public ErrorDecoder errorDecoder(ObjectMapper objectMapper) {
        return new FeignErrorDecoder(objectMapper);
    }
}
