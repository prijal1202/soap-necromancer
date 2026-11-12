package dev.soapnecromancer.web;

import org.springframework.http.HttpStatus;

/**
 * An upload the generator refuses before or instead of parsing it (empty, too
 * large, server busy).
 */
final class BadUpload extends RuntimeException {
    final HttpStatus status;
    /** Seconds for the Retry-After header, or 0 for none. */
    final int retryAfterSeconds;

    BadUpload(HttpStatus status, String message) {
        this(status, message, 0);
    }

    private BadUpload(HttpStatus status, String message, int retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static BadUpload busy(String message, int retryAfterSeconds) {
        return new BadUpload(HttpStatus.SERVICE_UNAVAILABLE, message, retryAfterSeconds);
    }
}
