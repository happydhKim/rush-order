package com.rushorder.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.GlobalExceptionHandler;
import com.rushorder.common.exception.InsufficientStockException;
import com.rushorder.inventory.dto.StockReserveRequest;
import com.rushorder.inventory.dto.StockReserveRequest.StockItem;
import com.rushorder.inventory.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InventoryController 단위 테스트 (MockMvc).
 *
 * <p>HTTP 요청/응답의 직렬화, 상태 코드, 예외 매핑을 검증한다.
 * 비즈니스 로직은 InventoryService를 Mock으로 대체하여 분리한다.
 */
@WebMvcTest(InventoryController.class)
@Import(GlobalExceptionHandler.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InventoryService inventoryService;

    @Nested
    @DisplayName("POST /api/inventories/reserve")
    class Reserve {

        @Test
        @DisplayName("정상 예약 요청 시 200 OK를 반환한다")
        void shouldReturn200OnSuccessfulReserve() throws Exception {
            // 유효한 예약 요청이 오면 서비스 호출 후 200 OK와 success=true를 반환해야 한다.
            willDoNothing().given(inventoryService).reserveStock(any(StockReserveRequest.class));

            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(new StockItem(1L, 5))
            );

            mockMvc.perform(post("/api/inventories/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("재고 부족 시 409 Conflict를 반환한다")
        void shouldReturn409OnInsufficientStock() throws Exception {
            // 재고 부족 시 InsufficientStockException이 발생하고,
            // GlobalExceptionHandler가 이를 409 Conflict로 매핑한다.
            willThrow(new InsufficientStockException("1", 3, 10))
                    .given(inventoryService).reserveStock(any(StockReserveRequest.class));

            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(new StockItem(1L, 10))
            );

            mockMvc.perform(post("/api/inventories/reserve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));
        }
    }

    @Nested
    @DisplayName("POST /api/inventories/{menuId}/confirm")
    class Confirm {

        @Test
        @DisplayName("정상 확정 요청 시 200 OK를 반환한다")
        void shouldReturn200OnConfirm() throws Exception {
            // Saga 오케스트레이터가 결제 성공 후 확정 API를 호출하는 시나리오.
            willDoNothing().given(inventoryService).confirmStock("order-1", 1L, 5);

            mockMvc.perform(post("/api/inventories/1/confirm")
                            .param("orderId", "order-1")
                            .param("quantity", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("POST /api/inventories/{menuId}/release")
    class Release {

        @Test
        @DisplayName("정상 해제 요청 시 200 OK를 반환한다")
        void shouldReturn200OnRelease() throws Exception {
            // Saga 보상 트랜잭션으로 재고를 해제하는 시나리오.
            willDoNothing().given(inventoryService).releaseStock("order-1", 1L, 5);

            mockMvc.perform(post("/api/inventories/1/release")
                            .param("orderId", "order-1")
                            .param("quantity", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}
