package com.sentinel.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PodInfoTest {

    private static Pod pod(String name, String ns, Map<String, String> labels, io.fabric8.kubernetes.api.model.OwnerReference... owners) {
        return new PodBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).withLabels(labels)
            .withOwnerReferences(owners).endMetadata()
            .build();
    }

    private static io.fabric8.kubernetes.api.model.OwnerReference owner(String kind, String name, boolean controller) {
        return new OwnerReferenceBuilder().withApiVersion("apps/v1").withKind(kind).withName(name)
            .withUid("u-" + name).withController(controller).build();
    }

    @Test
    void deploymentReplicasAllResolveToTheDeployment() {
        Map<String, String> labels = Map.of("pod-template-hash", "7d6495546c");
        Pod a = pod("web-7d6495546c-aaaaa", "default", labels, owner("ReplicaSet", "web-7d6495546c", true));
        Pod b = pod("web-7d6495546c-bbbbb", "default", labels, owner("ReplicaSet", "web-7d6495546c", true));

        assertEquals("default/Deployment/web", PodInfo.workloadIdFor(a, "default/web-7d6495546c-aaaaa"));
        assertEquals(PodInfo.workloadIdFor(a, "x"), PodInfo.workloadIdFor(b, "y"), "replicas are one problem");
    }

    @Test
    void deploymentNameWithHyphensIsStrippedOfOnlyTheHash() {
        Pod p = pod("my-cool-app-59f8b7c5d-x1", "prod", Map.of("pod-template-hash", "59f8b7c5d"),
            owner("ReplicaSet", "my-cool-app-59f8b7c5d", true));
        assertEquals("prod/Deployment/my-cool-app", PodInfo.workloadIdFor(p, "fallback"));
    }

    @Test
    void replicaSetWithoutADeploymentHashStaysAReplicaSet() {
        Pod p = pod("rs-x", "default", Map.of(), owner("ReplicaSet", "standalone-rs", true));
        assertEquals("default/ReplicaSet/standalone-rs", PodInfo.workloadIdFor(p, "fallback"));

        Pod mismatched = pod("rs-y", "default", Map.of("pod-template-hash", "zzz"), owner("ReplicaSet", "standalone-rs", true));
        assertEquals("default/ReplicaSet/standalone-rs", PodInfo.workloadIdFor(mismatched, "fallback"),
            "hash that isn't the RS name's suffix must not be stripped");
    }

    @Test
    void otherControllersUseTheirOwnKindAndName() {
        assertEquals("data/StatefulSet/db",
            PodInfo.workloadIdFor(pod("db-0", "data", Map.of(), owner("StatefulSet", "db", true)), "f"));
        assertEquals("kube-x/DaemonSet/agent",
            PodInfo.workloadIdFor(pod("agent-abc", "kube-x", Map.of(), owner("DaemonSet", "agent", true)), "f"));
        assertEquals("default/Job/migrate",
            PodInfo.workloadIdFor(pod("migrate-xyz", "default", Map.of(), owner("Job", "migrate", true)), "f"));
    }

    @Test
    void controllerOwnerWinsOverOtherOwners() {
        Pod p = pod("p", "default", Map.of(), owner("ConfigMap", "cfg", false), owner("StatefulSet", "db", true));
        assertEquals("default/StatefulSet/db", PodInfo.workloadIdFor(p, "f"));
    }

    @Test
    void barePodAndMissingDataFallBackToThePodItself() {
        assertEquals("default/bare", PodInfo.workloadIdFor(pod("bare", "default", Map.of()), "default/bare"));
        assertEquals("default/x", PodInfo.workloadIdFor(null, "default/x"));
        assertEquals("default/x", PodInfo.workloadIdFor(new Pod(), "default/x"));
    }

    @Test
    void theEightArgumentConstructorDerivesTheWorkload() {
        Pod p = pod("web-abc12-x", "default", Map.of("pod-template-hash", "abc12"), owner("ReplicaSet", "web-abc12", true));
        PodInfo info = new PodInfo("default/web-abc12-x", "web-abc12-x", "default", "Running", "Error", 2, "HIGH", p);
        assertEquals("default/Deployment/web", info.workloadId());
        assertEquals("default/x", new PodInfo("default/x", "x", "default", "Running", "Error", 0, "MEDIUM", null).workloadId());
    }
}
