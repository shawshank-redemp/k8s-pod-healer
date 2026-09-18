package com.sentinel.diagnosis;

/** All fields nullable - only the ones Kubernetes actually set are populated. */
public record ProbeInfo(
    Integer initialDelaySeconds, Integer periodSeconds, Integer timeoutSeconds, Integer failureThreshold) {}
