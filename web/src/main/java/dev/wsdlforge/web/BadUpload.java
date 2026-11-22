package dev.wsdlforge.web;

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

    /**
     * 413 for an upload over the cap, with the cap in the units people read ("2
     * MB").
     */
    static BadUpload tooLarge(int maxBytes) {
        return new BadUpload(HttpStatus.PAYLOAD_TOO_LARGE,
                "The upload is larger than the " + readableSize(maxBytes) + " limit.");
    }

    /**
     * Whole MB or KB when the size is an exact multiple (binary units), otherwise
     * bytes.
     */
    static String readableSize(long bytes) {
        if (bytes > 0 && bytes % (1024 * 1024) == 0)
            return bytes / (1024 * 1024) + " MB";
        if (bytes > 0 && bytes % 1024 == 0)
            return bytes / 1024 + " KB";
        return String.format(java.util.Locale.ROOT, "%,d bytes", bytes);
    }
}
