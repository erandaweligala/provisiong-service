package com.axonect.aee.template.baseapp.domain.exception;

import com.axonect.aee.template.baseapp.application.constants.LoggingAdviceConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AAAException.class)
    public ResponseEntity<Map<String, Object>> handleAAAException(AAAException ex) {
        log.warn(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error_code", ex.getCode());
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        log.warn(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        String ValidationError = ex.getBindingResult().getAllErrors().stream().map(
                error -> {
                    String errorMessage = error.getDefaultMessage();
                    return errorMessage;
                }
        ).collect(Collectors.joining(", "));

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error_code", "AAA_400_BAD_REQUEST");
        response.put("message", "Invalid or missing parameter in API request. "+ ValidationError);
        response.put("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }


    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error_code", "AAA_400_BAD_REQUEST");
        response.put("message", "Input contains invalid content.");
        response.put("timestamp", Instant.now().toString());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error_code", "AAA_500_INTERNAL_ERROR");
        body.put("message", "An internal server error occurred.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.internalServerError().body(body);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        Map<String, Object> response = new HashMap<>();
        String fieldName = ex.getName();  // The name of the field that failed

        response.put("success", false);
        response.put("error_code", "AAA_400_BAD_REQUEST");
        response.put("message",
                "Invalid value for field '" + fieldName + "'. Expected an integer value.");
        response.put("timestamp", Instant.now().toString());

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParseError(HttpMessageNotReadableException ex) {
        log.warn(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, 0, ex.getMessage(), ex.getClass().getSimpleName());
        String message = "Invalid request body. Please check JSON types.";

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error_code", "AAA_400_BAD_REQUEST");
        response.put("message", message);
        response.put("timestamp", Instant.now().toString());

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }



}
