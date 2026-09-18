package com.sentinel.diagnosis;

import com.sentinel.detection.PodInfo;

/** Minimal shape DetectionModule needs, so it can depend on an interface rather than the
 * concrete DiagnosisModule (keeps the two packages loosely coupled, same as the mock/real
 * client split - useful for injecting a test spy in DetectionModule's own tests). */
public interface DiagnosisModuleLike {
    DiagnosisResult diagnose(PodInfo podInfo);
}
