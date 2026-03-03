package com.axonect.aee.template.baseapp.domain.exception;

import org.springframework.http.HttpStatus;
import lombok.Getter;

@Getter
public class AAAException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AAAException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
