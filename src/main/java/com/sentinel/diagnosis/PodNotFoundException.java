package com.sentinel.diagnosis;

/** Thrown when a pod no longer exists (deleted before/during diagnosis). */
public class PodNotFoundException extends RuntimeException {
    public PodNotFoundException(String message) {
        super(message);
    }

    public PodNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
