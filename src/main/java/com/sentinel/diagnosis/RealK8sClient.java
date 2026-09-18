package com.sentinel.diagnosis;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.ResourceNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Talks to an actual cluster via the fabric8 kubernetes-client. */
public class RealK8sClient implements K8sDataFetcher {

    private static final int MAX_EVENTS = 10;

    private final KubernetesClient client;

    /** Uses fabric8's own Config.autoConfigure() - it checks env vars and file existence
     * properly (kubeconfig first, then in-cluster service-account files), which is more
     * defensive than the naive "try in-cluster, catch, fall back" pattern this project's
     * TypeScript port used and got bitten by (that client's in-cluster loader didn't validate
     * the env vars it read, so it "succeeded" with a garbage URL on a non-cluster machine). */
    public RealK8sClient() {
        this.client = new KubernetesClientBuilder().build();
    }

    public RealK8sClient(String kubeconfigPath) {
        this.client = new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kubeconfigPath)).build();
    }

    @Override
    public String getPodStatus(String namespace, String podName) {
        Pod pod;
        try {
            pod = client.pods().inNamespace(namespace).withName(podName).require();
        } catch (ResourceNotFoundException e) {
            throw new PodNotFoundException("pod " + namespace + "/" + podName + " not found", e);
        }
        return pod.getStatus() != null && pod.getStatus().getPhase() != null
            ? pod.getStatus().getPhase()
            : "Unknown";
    }

    @Override
    public String fetchPodLogs(String namespace, String podName, String container, boolean previous) {
        try {
            var podResource = client.pods().inNamespace(namespace).withName(podName);
            if (container != null) {
                var containerResource = podResource.inContainer(container);
                return previous
                    ? containerResource.terminated().tailingLines(500).getLog()
                    : containerResource.tailingLines(500).getLog();
            }
            return previous
                ? podResource.terminated().tailingLines(500).getLog()
                : podResource.tailingLines(500).getLog();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String fetchInitContainerLogs(String namespace, String podName, String container) {
        try {
            return client.pods().inNamespace(namespace).withName(podName)
                .inContainer(container).tailingLines(200).getLog();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<EventInfo> fetchPodEvents(String namespace, String podName) {
        try {
            List<Event> items = client.v1().events().inNamespace(namespace)
                .withField("involvedObject.name", podName)
                .list()
                .getItems();
            int from = Math.max(0, items.size() - MAX_EVENTS);
            return items.subList(from, items.size()).stream()
                .map(e -> new EventInfo(
                    e.getReason() != null ? e.getReason() : "Unknown",
                    e.getMessage() != null ? e.getMessage() : "",
                    e.getLastTimestamp() != null ? e.getLastTimestamp()
                        : (e.getEventTime() != null ? e.getEventTime().toString() : "")))
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public PodSpecInfo fetchPodSpec(Pod podObject) {
        try {
            Container container = podObject.getSpec().getContainers().get(0);
            Map<String, Quantity> limits = container.getResources() != null && container.getResources().getLimits() != null
                ? container.getResources().getLimits() : Map.of();
            Map<String, Quantity> requests = container.getResources() != null && container.getResources().getRequests() != null
                ? container.getResources().getRequests() : Map.of();

            Map<String, String> envVars = new HashMap<>();
            if (container.getEnv() != null) {
                for (var e : container.getEnv()) {
                    if (e.getValue() != null) envVars.put(e.getName(), e.getValue());
                }
            }

            return new PodSpecInfo(
                container.getImage() != null ? container.getImage() : "unknown",
                limits.containsKey("memory") ? limits.get("memory").toString() : "not set",
                limits.containsKey("cpu") ? limits.get("cpu").toString() : "not set",
                requests.containsKey("memory") ? requests.get("memory").toString() : "not set",
                requests.containsKey("cpu") ? requests.get("cpu").toString() : "not set",
                envVars,
                probeToInfo(container.getLivenessProbe()),
                probeToInfo(container.getReadinessProbe()));
        } catch (Exception e) {
            return PodSpecInfo.unknown();
        }
    }

    private static ProbeInfo probeToInfo(Probe probe) {
        if (probe == null) return null;
        return new ProbeInfo(
            probe.getInitialDelaySeconds(), probe.getPeriodSeconds(),
            probe.getTimeoutSeconds(), probe.getFailureThreshold());
    }
}
