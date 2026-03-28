package com.rushorder.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.dto.ApiResponse;
import com.rushorder.common.exception.InsufficientStockException;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.common.exception.ServiceUnavailableException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;

/**
 * Feign 호출 실패 시 HTTP 상태 코드를 도메인 예외로 변환한다.
 *
 * <p>기본 ErrorDecoder는 모든 에러를 FeignException으로 전파하여
 * 클라이언트에게 의미 없는 에러 메시지를 반환한다.
 * 이 디코더는 응답 body의 {@link ApiResponse.ErrorDetail}을 파싱하여
 * 비즈니스 예외(4xx)와 인프라 장애(5xx)를 명확히 구분한다.
 *
 * <p>4xx: 클라이언트 요청 문제이므로 재시도하지 않아야 한다.
 * 5xx: 다운스트림 장애이므로 Retry/CircuitBreaker 대상이 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class FeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;
    private final ErrorDecoder defaultDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        ApiResponse.ErrorDetail errorDetail = extractErrorDetail(response);
        int status = response.status();

        if (status >= 400 && status < 500) {
            return handleClientError(methodKey, status, errorDetail);
        }

        if (status >= 500) {
            String detail = errorDetail != null ? errorDetail.message() : "Unknown server error";
            log.error("Downstream 5xx error: method={}, status={}, detail={}", methodKey, status, detail);
            return new ServiceUnavailableException(extractServiceName(methodKey), detail);
        }

        return defaultDecoder.decode(methodKey, response);
    }

    /**
     * 4xx 에러를 에러 코드에 따라 적절한 비즈니스 예외로 변환한다.
     *
     * <p>응답 body에 에러 코드가 없거나 매핑되지 않는 코드인 경우
     * 기본 FeignException으로 폴백한다.
     */
    private Exception handleClientError(String methodKey, int status, ApiResponse.ErrorDetail errorDetail) {
        if (errorDetail == null) {
            log.warn("4xx error without parseable body: method={}, status={}", methodKey, status);
            return new RuntimeException(
                    String.format("Client error from %s: status=%d", extractServiceName(methodKey), status));
        }

        String errorCode = errorDetail.code();
        String message = errorDetail.message();
        log.warn("Downstream 4xx error: method={}, code={}, message={}", methodKey, errorCode, message);

        return switch (errorCode) {
            case "INSUFFICIENT_STOCK" -> new InsufficientStockException("unknown", 0, 0);
            case "NOT_FOUND" -> new NotFoundException("Resource", message);
            default -> new RuntimeException(
                    String.format("[%s] %s", errorCode, message));
        };
    }

    private ApiResponse.ErrorDetail extractErrorDetail(Response response) {
        if (response.body() == null) {
            return null;
        }

        try (InputStream is = response.body().asInputStream()) {
            ApiResponse<?> apiResponse = objectMapper.readValue(is, ApiResponse.class);
            return apiResponse.error();
        } catch (IOException e) {
            log.debug("Failed to parse error response body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Feign methodKey에서 서비스명을 추출한다.
     * methodKey 형식: "InventoryClient#reserveStock(StockReserveCommand)"
     */
    private String extractServiceName(String methodKey) {
        int hashIndex = methodKey.indexOf('#');
        return hashIndex > 0 ? methodKey.substring(0, hashIndex) : methodKey;
    }
}
