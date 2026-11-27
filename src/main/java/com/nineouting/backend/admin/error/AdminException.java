package com.nineouting.backend.admin.error;

import org.springframework.http.HttpStatus;

/** 화면에 그대로 보여 줄 오류. code는 화면이 구분할 때 쓴다 */
public class AdminException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AdminException notFound(String message) {
        return new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static AdminException badRequest(String message) {
        return new AdminException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public static AdminException readOnly(String message) {
        return new AdminException(HttpStatus.FORBIDDEN, "READ_ONLY", message);
    }
}
