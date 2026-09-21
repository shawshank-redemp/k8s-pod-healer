package com.sentinel.detection;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;
import java.util.Map;

/**
 * Safely-extracted, downstream-ready summary of a pod (see DetectionModule.extractPodInfo).
 *
 * @param workloadId identifies the thing that OWNS the pod, e.g. {@code default/Deployment/web}.
 *     Three crashing replicas of one Deployment are one problem with one fix, so agent runs and
 *     suppression are keyed on this rather than on the (per-replica) pod name.
 */
public record PodInfo(
    String podId,
    String podName,
    String namespace,
    String status,
    String reason,
    int restartCount,
    String severity,
    Pod podObject,
    String workloadId) {

    /** Derives {@code workloadId} from the pod's owner references. */
    public PodInfo(
        String podId, String podName, String namespace, String status, String reason,
        int restartCount, String severity, Pod podObject) {
        this(podId, podName, namespace, status, reason, restartCount, severity, podObject,
            workloadIdFor(podObject, podId));
    }

    /**
     * The controller that owns this pod, as {@code namespace/Kind/name}; falls back to the pod
     * itself when it has no owner (a bare pod) or the pod object is unavailable.
     *
     * <p>Pods of a Deployment are owned by a ReplicaSet named {@code <deployment>-<hash>}, where
     * the hash is also the pod's {@code pod-template-hash} label. When that pairing holds we
     * report the Deployment, since that's what an operator would fix.
     */
    public static String workloadIdFor(Pod pod, String fallbackPodId) {
        try {
            if (pod == null || pod.getMetadata() == null) return fallbackPodId;
            List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
            if (owners == null || owners.isEmpty()) return fallbackPodId;

            OwnerReference owner = owners.stream()
                .filter(o -> Boolean.TRUE.equals(o.getController()))
                .findFirst()
                .orElse(owners.get(0));
            if (owner.getKind() == null || owner.getName() == null) return fallbackPodId;

            String namespace = pod.getMetadata().getNamespace() != null ? pod.getMetadata().getNamespace() : "unknown";
            String kind = owner.getKind();
            String name = owner.getName();

            if ("ReplicaSet".equals(kind)) {
                Map<String, String> labels = pod.getMetadata().getLabels();
                String hash = labels != null ? labels.get("pod-template-hash") : null;
                if (hash != null && name.endsWith("-" + hash) && name.length() > hash.length() + 1) {
                    kind = "Deployment";
                    name = name.substring(0, name.length() - hash.length() - 1);
                }
            }
            return namespace + "/" + kind + "/" + name;
        } catch (Exception e) {
            return fallbackPodId;
        }
    }
}
