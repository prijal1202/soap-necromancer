package dev.soapnecromancer.web;

import org.springframework.http.HttpStatus;

/** An upload the generator refuses before or instead of parsing it (empty, too large, server busy). */
final class BadUpload extends RuntimeException {
    final HttpStatus status;

    BadUpload(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
