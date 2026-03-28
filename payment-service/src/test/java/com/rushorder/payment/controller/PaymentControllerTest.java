package com.rushorder.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.GlobalExceptionHandler;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.payment.domain.PaymentStatus;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentController MockMvc 테스트.
 *
 * <p>HTTP 요청/응답 매핑, Validation, 예외 처리를 검증한다.
 * Service 계층은 MockBean으로 격리하여 컨트롤러의 역할만 테스트한다.
 */
@WebMvcTest(PaymentController.class)
@Import(GlobalExceptionHandler.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    @Nested
    @DisplayName("POST /api/payments - 결제 처리")
    class ProcessPayment {

        @Test
        @DisplayName("결제 승인 성공 시 201 Created와 APPROVED 상태를 반환한다")
        void shouldReturn201WhenPaymentApproved() throws Exception {
            // 정상 결제 요청이 들어오면 201 상태 코드와 함께
            // ApiResponse 래퍼에 감싸진 PaymentResponse를 반환해야 한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);
            PaymentResponse response = new PaymentResponse(
                    1L, "order-1", 10000, PaymentStatus.APPROVED,
                    "PG-abc", null, LocalDateTime.now()
            );

            given(paymentService.processPayment(any(PaymentRequest.class))).willReturn(response);

            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"))
                    .andExpect(jsonPath("$.data.pgTransactionId").value("PG-abc"))
                    .andExpect(jsonPath("$.data.orderId").value("order-1"));
        }

        @Test
        @DisplayName("결제가 PENDING 상태로 처리되어도 201을 반환한다")
        void shouldReturn201WhenPaymentPending() throws Exception {
            // PG 장애로 fallback이 동작하여 PENDING 상태가 되더라도
            // HTTP 계층에서는 정상 응답(201)으로 처리된다.
            // 결제 상태에 따른 후속 처리는 Saga가 담당한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);
            PaymentResponse response = new PaymentResponse(
                    1L, "order-1", 10000, PaymentStatus.PENDING,
                    null, null, LocalDateTime.now()
            );

            given(paymentService.processPayment(any(PaymentRequest.class))).willReturn(response);

            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("필수 필드 누락 시 400 Bad Request를 반환한다")
        void shouldReturn400WhenValidationFails() throws Exception {
            // @Valid 어노테이션으로 Request DTO의 유효성을 검증한다.
            // orderId가 빈 문자열이면 @NotBlank 위반으로 400 응답이 반환되어야 한다.
            PaymentRequest request = new PaymentRequest("", "idem-1", 10000);

            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("GET /api/payments/orders/{orderId} - 결제 조회")
    class GetPayment {

        @Test
        @DisplayName("존재하는 주문의 결제를 조회하면 200 OK를 반환한다")
        void shouldReturn200WhenPaymentExists() throws Exception {
            // 정상적인 결제 조회 요청에 대해 200 상태 코드와 결제 정보를 반환한다.
            PaymentResponse response = new PaymentResponse(
                    1L, "order-1", 10000, PaymentStatus.APPROVED,
                    "PG-abc", null, LocalDateTime.now()
            );

            given(paymentService.getPayment("order-1")).willReturn(response);

            mockMvc.perform(get("/api/payments/orders/order-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.orderId").value("order-1"))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        @Test
        @DisplayName("존재하지 않는 주문의 결제를 조회하면 404 Not Found를 반환한다")
        void shouldReturn404WhenPaymentNotFound() throws Exception {
            // NotFoundException이 GlobalExceptionHandler에 의해
            // 404 상태 코드와 에러 응답으로 변환되는지 검증한다.
            given(paymentService.getPayment("nonexistent"))
                    .willThrow(new NotFoundException("Payment", "nonexistent"));

            mockMvc.perform(get("/api/payments/orders/nonexistent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }
}
