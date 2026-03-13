package com.rushorder.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청한 리소스가 존재하지 않을 때 발생하는 예외.
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String resourceType, String resourceId) {
        super(
                "NOT_FOUND",
                String.format("%s not found: %s", resourceType, resourceId),
                HttpStatus.NOT_FOUND
        );
    }
}
