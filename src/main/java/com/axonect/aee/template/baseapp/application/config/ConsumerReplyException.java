package com.axonect.aee.template.baseapp.application.config;

public class ConsumerReplyException extends RuntimeException {

    public ConsumerReplyException(String message) {
        super(message);
    }

    public ConsumerReplyException(String message, Throwable cause) {
        super(message, cause);
    }
}