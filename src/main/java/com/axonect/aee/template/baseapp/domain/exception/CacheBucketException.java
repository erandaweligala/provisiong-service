package com.axonect.aee.template.baseapp.domain.exception;

import lombok.Getter;

@Getter
public class CacheBucketException extends RuntimeException {

    private final String bucketUsername;
    private final Integer statusCode;
    private final String bucketId;

    public CacheBucketException(String message, String bucketUsername, Integer statusCode) {
        super(message);
        this.bucketUsername = bucketUsername;
        this.statusCode = statusCode;
        this.bucketId = null;
    }

    public CacheBucketException(String message, String bucketUsername, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.bucketUsername = bucketUsername;
        this.statusCode = statusCode;
        this.bucketId = null;
    }

    public CacheBucketException(String message, String bucketUsername, String bucketId, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.bucketUsername = bucketUsername;
        this.statusCode = statusCode;
        this.bucketId = bucketId;
    }
}