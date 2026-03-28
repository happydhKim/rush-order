package com.rushorder.restaurant.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.restaurant.document.RestaurantDocument;
import com.rushorder.restaurant.service.RestaurantSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RestaurantEventConsumer 단위 테스트.
 *
 * <p>Kafka를 통해 수신된 가게 변경 이벤트가 올바르게 역직렬화되어
 * ES에 인덱싱되는지 검증한다. CQRS 패턴에서 Write -> Read 동기화의
 * 마지막 단계에 해당한다.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantEventConsumerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RestaurantSearchService restaurantSearchService;

    @InjectMocks
    private RestaurantEventConsumer restaurantEventConsumer;

    @Test
    @DisplayName("restaurant-updated 이벤트 수신 시 ES에 인덱싱한다")
    void consumeRestaurantEvent_validMessage_indexesToEs() throws Exception {
        // 정상적인 JSON 메시지가 수신되면 RestaurantDocument로 역직렬화한 후
        // RestaurantSearchService.indexRestaurant()을 통해 ES에 인덱싱해야 한다.
        String message = "{\"restaurantId\":1,\"restaurantName\":\"치킨집\"}";
        RestaurantDocument document = RestaurantDocument.builder()
                .restaurantId(1L)
                .restaurantName("치킨집")
                .build();
        given(objectMapper.readValue(message, RestaurantDocument.class)).willReturn(document);

        restaurantEventConsumer.consumeRestaurantEvent(message);

        verify(restaurantSearchService).indexRestaurant(document);
    }

    @Test
    @DisplayName("JSON 역직렬화 실패 시 예외를 삼키고 인덱싱하지 않는다")
    void consumeRestaurantEvent_invalidJson_swallowsException() throws Exception {
        // 잘못된 JSON이 수신되면 예외를 삼켜서 Kafka Consumer가 계속 동작하도록 한다.
        // 예외를 throw하면 Consumer가 재시도 루프에 빠질 수 있기 때문이다.
        // DLQ(Dead Letter Queue) 도입 전까지는 로그로만 추적한다.
        String invalidMessage = "not-valid-json";
        given(objectMapper.readValue(invalidMessage, RestaurantDocument.class))
                .willThrow(new RuntimeException("Parse error"));

        // 예외가 외부로 전파되지 않아야 한다
        restaurantEventConsumer.consumeRestaurantEvent(invalidMessage);

        verify(restaurantSearchService, never()).indexRestaurant(any());
    }

    @Test
    @DisplayName("인덱싱 실패 시 예외를 삼켜서 Consumer 중단을 방지한다")
    void consumeRestaurantEvent_indexingFails_swallowsException() throws Exception {
        // ES 인덱싱 자체가 실패해도 Consumer가 중단되지 않아야 한다.
        // 이벤트 소실 가능성이 있지만, Consumer 중단보다 낫다.
        String message = "{\"restaurantId\":1}";
        RestaurantDocument document = RestaurantDocument.builder().restaurantId(1L).build();
        given(objectMapper.readValue(message, RestaurantDocument.class)).willReturn(document);
        // indexRestaurant에서 예외 발생 시 consumeRestaurantEvent의 catch 블록이 처리
        // (indexRestaurant 자체는 void이므로 doThrow 사용)
        org.mockito.Mockito.doThrow(new RuntimeException("ES indexing failed"))
                .when(restaurantSearchService).indexRestaurant(document);

        // 예외가 외부로 전파되지 않아야 한다
        restaurantEventConsumer.consumeRestaurantEvent(message);
    }
}
