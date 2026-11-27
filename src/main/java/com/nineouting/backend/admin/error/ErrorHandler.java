package com.nineouting.backend.admin.error;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류 응답은 {"code", "message"} 하나로 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(AdminException.class)
    ResponseEntity<Map<String, String>> admin(AdminException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    /** 제약 조건 위반, 형식 변환 실패 등. 관리자가 고칠 수 있게 DB 메시지를 그대로 보여 준다 */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException e) {
        String message = e.getMostSpecificCause().getMessage();
        log.warn("DB 오류: {}", message);
        return body(HttpStatus.BAD_REQUEST, "DB_ERROR", message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "필수 값이 비어 있습니다");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 본문을 읽을 수 없습니다");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }
}
