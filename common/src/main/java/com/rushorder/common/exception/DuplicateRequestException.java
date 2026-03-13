package com.rushorder.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 멱등키 중복으로 이미 처리된 요청이 재수신되었을 때 발생하는 예외.
 */
public class DuplicateRequestException extends BusinessException {

    public DuplicateRequestException(String idempotencyKey) {
        super(
                "DUPLICATE_REQUEST",
                String.format("Request already processed: %s", idempotencyKey),
                HttpStatus.CONFLICT
        );
    }
}
