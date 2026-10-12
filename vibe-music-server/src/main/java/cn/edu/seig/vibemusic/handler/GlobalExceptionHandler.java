package cn.edu.seig.vibemusic.handler;

import cn.edu.seig.vibemusic.result.Result;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> invalidBody(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct().limit(5).reduce((a, b) -> a + "; " + b).orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> invalidParameter(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Result.error(
                ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    @ExceptionHandler(JWTVerificationException.class)
    public ResponseEntity<Result<?>> invalidToken(JWTVerificationException ex) {
        return error(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, SQLIntegrityConstraintViolationException.class})
    public ResponseEntity<Result<?>> conflict(Exception ex) {
        return error(HttpStatus.CONFLICT, "数据冲突，请检查操作后重试");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable(Exception ex) {
        log.warn("数据服务暂不可用，类型={}", ex.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用，请稍后再试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse httpError) {
            return ResponseEntity.status(httpError.getStatusCode()).body(Result.error("请求无法处理"));
        }
        String errorId = UUID.randomUUID().toString();
        // 异常信息可能携带 SQL 参数、模型请求或凭据；仅记录分类与追踪标识。
        log.error("请求处理失败，errorId={}, type={}", errorId, ex.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务异常，请稍后重试（" + errorId + "）");
    }

    private ResponseEntity<Result<?>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.error(message));
    }
}
