package com.sentinel.diagnosis;

/** timestamp is nullable - a k8s Event's lastTimestamp/eventTime as a display string. */
public record EventInfo(String reason, String message, String timestamp) {
    public EventInfo(String reason, String message) {
        this(reason, message, null);
    }
}
