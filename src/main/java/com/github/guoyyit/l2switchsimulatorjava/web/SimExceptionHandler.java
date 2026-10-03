package com.github.guoyyit.l2switchsimulatorjava.web;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把非法参数/状态映射为 4xx，避免框架默认 500。 */
@RestControllerAdvice
public class SimExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    /** 回放被拒绝：返回可区分的 {@code code} 与说明，绝不静默返回空结果。 */
    @ExceptionHandler(ReplayRejectedException.class)
    public ResponseEntity<Map<String, String>> handleReplayRejected(ReplayRejectedException e) {
        HttpStatus status = ReplayRejectedException.VERIFICATION_FAILED.equals(e.code())
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", e.code(), "error", e.getMessage()));
    }
}
