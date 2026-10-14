package com.enterprise.knowledge;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> response(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason())); }
    @ExceptionHandler(AuthenticationException.class) ResponseEntity<?> auth(Exception e) { return ResponseEntity.status(401).body(Map.of("message","用户名或密码错误")); }
    @ExceptionHandler({MethodArgumentNotValidException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","请求参数不合法，请检查输入")); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("message","数据冲突，名称可能已存在")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large(Exception e) { return ResponseEntity.status(413).body(Map.of("message","文件不能超过 20 MB")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e) {
        LoggerFactory.getLogger(ApiErrors.class).error("请求处理失败",e);
        return ResponseEntity.internalServerError().body(Map.of("message","服务处理失败，请联系管理员查看日志"));
    }
}
