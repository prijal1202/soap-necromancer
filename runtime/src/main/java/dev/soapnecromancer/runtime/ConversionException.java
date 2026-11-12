package dev.soapnecromancer.runtime;

/** Invalid JSON input or unconvertible XML. {@code path} is JSONPath-like, e.g. {@code $.line[2].amount}. */
public class ConversionException extends RuntimeException {
    private final String path;

    public ConversionException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
