package com.brianmehrman.harness.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile("api")
public class ApiErrors {
    @ExceptionHandler({RunController.BadInput.class, RunController.Missing.class, RunController.Conflict.class})
    ResponseEntity<?> error(RuntimeException error) {
        HttpStatus status = error instanceof RunController.Missing ? HttpStatus.NOT_FOUND :
                error instanceof RunController.Conflict ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", error.getMessage()));
    }
}
