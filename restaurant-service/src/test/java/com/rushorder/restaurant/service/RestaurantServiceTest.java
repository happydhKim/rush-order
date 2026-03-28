package com.rushorder.restaurant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.domain.OutboxEvent;
import com.rushorder.restaurant.domain.Restaurant;
import com.rushorder.restaurant.dto.MenuRequest;
import com.rushorder.restaurant.dto.RestaurantRequest;
import com.rushorder.restaurant.dto.RestaurantResponse;
import com.rushorder.restaurant.repository.MenuRepository;
import com.rushorder.restaurant.repository.OutboxEventRepository;
import com.rushorder.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * RestaurantService 단위 테스트.
 *
 * <p>CQRS Write Path의 핵심 로직을 검증한다.
 * 모든 쓰기 연산이 PostgreSQL에 저장되고,
 * 같은 트랜잭션 내에서 Outbox 이벤트가 함께 INSERT되는지 확인한다.
 *
 * <p>[면접 포인트] Restaurant도 Outbox를 사용하여 CQRS 동기화하는 이유
 * - DB 저장과 Kafka 발행은 서로 다른 시스템이므로 원자적으로 수행할 수 없다.
 * - DB commit 후 Kafka 발행이 실패하면 ES에 반영이 안 되어 데이터 불일치가 발생한다.
 * - Outbox 패턴은 같은 TX에서 비즈니스 데이터 + 이벤트를 INSERT하고,
 *   별도 워커가 Outbox 테이블을 폴링하여 Kafka로 발행한다.
 * - 이렇게 하면 DB TX의 원자성을 이용하여 "저장 + 이벤트 발행"의 정합성을 보장한다.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private MenuRepository menuRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RestaurantService restaurantService;

    private RestaurantRequest createRestaurantRequest() {
        return new RestaurantRequest(
                "맛있는 치킨집", "서울시 강남구", "02-1234-5678",
                "치킨", 37.5665, 126.9780
        );
    }

    private Restaurant createRestaurant() {
        return new Restaurant(
                "맛있는 치킨집", "서울시 강남구", "02-1234-5678",
                "치킨", 37.5665, 126.9780
        );
    }

    @Test
    @DisplayName("가게 등록 시 Restaurant 저장과 Outbox 이벤트가 같은 TX에서 생성된다")
    void createRestaurant_savesRestaurantAndOutboxEvent() {
        // 가게 등록은 Restaurant INSERT와 Outbox INSERT가 같은 트랜잭션에서 수행되어야 한다.
        // 이를 통해 DB 저장과 이벤트 발행의 원자성을 보장한다.
        RestaurantRequest request = createRestaurantRequest();
        given(restaurantRepository.save(any(Restaurant.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(outboxEventRepository.save(any(OutboxEvent.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RestaurantResponse response = restaurantService.createRestaurant(request);

        // Restaurant 저장 검증
        verify(restaurantRepository).save(any(Restaurant.class));
        assertThat(response.name()).isEqualTo("맛있는 치킨집");

        // Outbox 이벤트 저장 검증: topic은 "restaurant-updated", eventType은 "RESTAURANT_CREATED"
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getTopic()).isEqualTo("restaurant-updated");
        assertThat(outboxEvent.getEventType()).isEqualTo("RESTAURANT_CREATED");
        assertThat(outboxEvent.isPublished()).isFalse();
    }

    @Test
    @DisplayName("메뉴 추가 시 Menu 저장과 Outbox 이벤트가 생성된다")
    void addMenu_savesMenuAndOutboxEvent() {
        // 메뉴 추가 시 cascade로 Menu가 저장되고,
        // "MENU_ADDED" 타입의 Outbox 이벤트가 함께 생성되어야 한다.
        Restaurant restaurant = createRestaurant();
        MenuRequest menuRequest = new MenuRequest("양념치킨", "매콤한 양념치킨", 18000);

        given(restaurantRepository.findById(any())).willReturn(Optional.of(restaurant));
        given(restaurantRepository.save(any(Restaurant.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(outboxEventRepository.save(any(OutboxEvent.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        restaurantService.addMenu(1L, menuRequest);

        // Restaurant.addMenu()을 통해 메뉴가 추가되었는지 확인
        assertThat(restaurant.getMenus()).hasSize(1);
        assertThat(restaurant.getMenus().get(0).getName()).isEqualTo("양념치킨");

        // Outbox 이벤트의 eventType이 MENU_ADDED인지 확인
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("MENU_ADDED");
    }

    @Test
    @DisplayName("존재하는 가게 조회 시 정상 응답을 반환한다")
    void getRestaurant_existingId_returnsResponse() {
        // 가게 ID로 조회 시 해당 가게 정보를 RestaurantResponse로 변환하여 반환해야 한다.
        Restaurant restaurant = createRestaurant();
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        RestaurantResponse response = restaurantService.getRestaurant(1L);

        assertThat(response.name()).isEqualTo("맛있는 치킨집");
        assertThat(response.category()).isEqualTo("치킨");
    }

    @Test
    @DisplayName("존재하지 않는 가게 조회 시 NotFoundException을 던진다")
    void getRestaurant_nonExistingId_throwsNotFoundException() {
        // 존재하지 않는 ID로 조회하면 NotFoundException이 발생해야 한다.
        // GlobalExceptionHandler에서 이를 404 응답으로 변환한다.
        given(restaurantRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRestaurant(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("가게 수정 시 정보가 업데이트되고 Outbox 이벤트가 생성된다")
    void updateRestaurant_updatesInfoAndCreatesOutboxEvent() {
        // 가게 정보 변경 시 엔티티 상태가 업데이트되고,
        // "RESTAURANT_UPDATED" Outbox 이벤트가 생성되어 ES 동기화를 트리거해야 한다.
        Restaurant restaurant = createRestaurant();
        RestaurantRequest updateRequest = new RestaurantRequest(
                "더 맛있는 치킨집", "서울시 서초구", "02-9876-5432",
                "치킨", 37.5665, 126.9780
        );
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));
        given(outboxEventRepository.save(any(OutboxEvent.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RestaurantResponse response = restaurantService.updateRestaurant(1L, updateRequest);

        assertThat(response.name()).isEqualTo("더 맛있는 치킨집");
        assertThat(response.address()).isEqualTo("서울시 서초구");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("RESTAURANT_UPDATED");
    }
}
