package com.rushorder.order.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.dto.ApiResponse;
import com.rushorder.common.exception.InsufficientStockException;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.common.exception.ServiceUnavailableException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FeignErrorDecoder 단위 테스트.
 *
 * <p>Feign 호출 실패 시 HTTP 상태 코드를 적절한 도메인 예외로 변환하는지 검증한다.
 * 4xx(클라이언트 에러)와 5xx(서버 에러)를 명확히 구분하여
 * 재시도 전략과 에러 핸들링을 올바르게 적용할 수 있어야 한다.
 *
 * <p>[면접 포인트] 4xx와 5xx를 구분하는 이유:
 * 4xx는 클라이언트 문제이므로 재시도해도 결과가 같다 (재시도 불필요).
 * 5xx는 서버 일시 장애일 수 있으므로 Retry/CircuitBreaker 대상이다.
 */
@DisplayName("FeignErrorDecoder 단위 테스트")
class FeignErrorDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeignErrorDecoder feignErrorDecoder;

    private static final String METHOD_KEY = "InventoryClient#reserveStock(StockReserveCommand)";

    @BeforeEach
    void setUp() {
        feignErrorDecoder = new FeignErrorDecoder(objectMapper);
    }

    /**
     * Feign Response 객체를 생성하는 헬퍼 메서드.
     * 실제 Feign이 생성하는 Response와 동일한 구조를 재현한다.
     */
    private Response createResponse(int status, String body) {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "http://localhost:8085/api/inventories/reserve",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        return Response.builder()
                .status(status)
                .reason("Test")
                .request(request)
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    private String createErrorBody(String code, String message) throws JsonProcessingException {
        ApiResponse<Void> apiResponse = ApiResponse.error(code, message);
        return objectMapper.writeValueAsString(apiResponse);
    }

    @Nested
    @DisplayName("4xx 클라이언트 에러")
    class ClientErrors {

        @Test
        @DisplayName("INSUFFICIENT_STOCK 에러 코드 -> InsufficientStockException으로 변환")
        void decode_400_insufficientStock_throwsInsufficientStockException() throws Exception {
            // Inventory Service가 재고 부족으로 400/409를 반환하면
            // FeignErrorDecoder가 InsufficientStockException으로 변환한다.
            // 이 예외는 OrderService에서 잡아서 사용자에게 의미 있는 에러 메시지를 전달한다.
            String body = createErrorBody("INSUFFICIENT_STOCK", "Menu 10: available=5, requested=10");
            Response response = createResponse(409, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("NOT_FOUND 에러 코드 -> NotFoundException으로 변환")
        void decode_404_notFound_throwsNotFoundException() throws Exception {
            // 요청한 리소스가 존재하지 않을 때 NotFoundException으로 변환한다
            String body = createErrorBody("NOT_FOUND", "Menu not found: 999");
            Response response = createResponse(404, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("알 수 없는 에러 코드 -> RuntimeException으로 변환")
        void decode_400_unknownCode_throwsRuntimeException() throws Exception {
            // 매핑되지 않은 에러 코드는 RuntimeException으로 폴백한다.
            // 새로운 에러 코드가 추가될 때 이 테스트가 경고 역할을 한다.
            String body = createErrorBody("VALIDATION_ERROR", "Invalid input");
            Response response = createResponse(400, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(RuntimeException.class);
            assertThat(exception.getMessage()).contains("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("응답 body가 없는 4xx -> RuntimeException으로 변환")
        void decode_400_noBody_throwsRuntimeException() {
            // 응답 body가 없거나 파싱 불가능한 경우에도 예외를 적절히 생성해야 한다
            Request request = Request.create(
                    Request.HttpMethod.POST,
                    "http://localhost:8085/api/inventories/reserve",
                    Collections.emptyMap(),
                    null,
                    StandardCharsets.UTF_8,
                    null
            );
            Response response = Response.builder()
                    .status(400)
                    .reason("Bad Request")
                    .request(request)
                    .headers(Collections.emptyMap())
                    .build();

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("5xx 서버 에러")
    class ServerErrors {

        @Test
        @DisplayName("500 에러 -> ServiceUnavailableException으로 변환")
        void decode_500_throwsServiceUnavailableException() throws Exception {
            // [면접 포인트] 5xx를 ServiceUnavailableException으로 변환하는 이유:
            // Resilience4j의 CircuitBreaker가 이 예외를 감지하여
            // 반복 장애 시 서킷을 오픈하고, 다운스트림 서비스에 대한 호출을 차단한다.
            // FeignException을 그대로 전파하면 CircuitBreaker가 이를 인식하지 못할 수 있다.
            String body = createErrorBody("INTERNAL_ERROR", "Database connection failed");
            Response response = createResponse(500, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(ServiceUnavailableException.class);
            assertThat(exception.getMessage()).contains("InventoryClient");
        }

        @Test
        @DisplayName("503 에러 -> ServiceUnavailableException으로 변환")
        void decode_503_throwsServiceUnavailableException() throws Exception {
            // 다운스트림 서비스가 일시적으로 사용 불가능한 경우
            String body = createErrorBody("SERVICE_UNAVAILABLE", "Service is overloaded");
            Response response = createResponse(503, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("500 에러 + 응답 body 없음 -> ServiceUnavailableException (Unknown server error)")
        void decode_500_noBody_throwsServiceUnavailableException() {
            // 응답 body가 없는 5xx 에러도 ServiceUnavailableException으로 변환해야 한다
            Request request = Request.create(
                    Request.HttpMethod.POST,
                    "http://localhost:8085/api/inventories/reserve",
                    Collections.emptyMap(),
                    null,
                    StandardCharsets.UTF_8,
                    null
            );
            Response response = Response.builder()
                    .status(500)
                    .reason("Internal Server Error")
                    .request(request)
                    .headers(Collections.emptyMap())
                    .build();

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            assertThat(exception).isInstanceOf(ServiceUnavailableException.class);
            assertThat(exception.getMessage()).contains("Unknown server error");
        }
    }

    @Nested
    @DisplayName("methodKey에서 서비스명 추출")
    class ServiceNameExtraction {

        @Test
        @DisplayName("methodKey 형식 'Client#method(...)' 에서 Client 이름을 추출한다")
        void decode_extractsServiceNameFromMethodKey() throws Exception {
            // FeignErrorDecoder가 methodKey에서 '#' 앞의 클라이언트명을 추출하여
            // 에러 메시지에 포함시킨다. 운영 시 어떤 다운스트림 서비스에서 에러가 났는지
            // 빠르게 파악할 수 있어야 한다.
            String body = createErrorBody("INTERNAL_ERROR", "timeout");
            Response response = createResponse(500, body);

            Exception exception = feignErrorDecoder.decode(METHOD_KEY, response);

            // "InventoryClient"가 에러 메시지에 포함되어야 한다
            assertThat(exception.getMessage()).contains("InventoryClient");
        }
    }
}
