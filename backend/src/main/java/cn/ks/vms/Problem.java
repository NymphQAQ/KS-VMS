package cn.ks.vms;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

final class Problem extends RuntimeException {
    final int status;
    final String code;

    Problem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static Problem bad(String message) {
        return new Problem(400, "INVALID_INPUT", message);
    }

    static Problem denied() {
        return new Problem(403, "ACCESS_DENIED", "当前账号无此操作权限");
    }

    static Problem missing() {
        return new Problem(404, "NOT_FOUND", "记录不存在或不可访问");
    }

    static Problem conflict(String message) {
        return new Problem(409, "CONFLICT", message);
    }
}

@RestControllerAdvice
class ProblemAdvice {
    @ExceptionHandler(Problem.class)
    ResponseEntity<Map<String, String>> failure(Problem error) {
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        if (error instanceof MethodArgumentNotValidException validation && validation.getFieldError() != null) {
            return failure(Problem.bad("请检查字段 " + validation.getFieldError().getField()));
        }
        return failure(Problem.bad("请求格式有误"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> integrity() {
        return failure(Problem.conflict("记录已存在或正在被使用，请刷新后重试"));
    }
}
