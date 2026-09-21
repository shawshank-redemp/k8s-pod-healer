package com.sentinel.trueforge;

/** A TrueForge API call failed (unreachable, non-2xx, or an unexpected response). */
public class TrueForgeException extends RuntimeException {

    private final int httpStatus;

    public TrueForgeException(String message) {
        this(message, -1, null);
    }

    public TrueForgeException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public TrueForgeException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP status of the failed call, or -1 if there was no HTTP response. */
    public int httpStatus() {
        return httpStatus;
    }
}
