package io.jafra.analyzer.recordings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@Startup
public class WorkloadIndex {
    private final ChunkStore store;
    private final ConcurrentHashMap<RecordingCatalog.WorkloadKey, WorkloadAccumulator> workloads = new ConcurrentHashMap<>();

    @Inject
    public WorkloadIndex(ChunkStore store) {
        this.store = store;
        store.onCommit(this::onChunkCommitted);
    }

    void onStartup(@Observes StartupEvent event) {
        rebuild();
    }

    public void rebuild() {
        workloads.clear();
        for (ChunkStore.StoredChunk stored : store.storedChunks()) {
            ChunkMetadata metadata = store.readMeta(stored);
            if (metadata != null) {
                apply(metadata, stored.payload());
            }
        }
    }

    public void onChunkCommitted(ChunkMetadata metadata) {
        if (metadata == null) {
            return;
        }
        Path payload = store.payloadPath(metadata.chunkId());
        apply(metadata, payload);
    }

    private void apply(ChunkMetadata metadata, Path payload) {
        String pod = store.podName(metadata.podUid(), metadata.podName());
        if (pod == null || pod.isBlank() || metadata.namespace() == null || metadata.namespace().isBlank()) {
            return;
        }
        String container = metadata.containerName();
        if (container == null || container.isBlank()) {
            return;
        }
        RecordingCatalog.WorkloadKey key =
                new RecordingCatalog.WorkloadKey(metadata.namespace(), pod, container);
        workloads.computeIfAbsent(key, ignored -> new WorkloadAccumulator())
                .add(metadata, payload);
    }

    public WorkloadDiscoveryDocument discover(String namespace, String pod, String container) {
        List<WorkloadSnapshot> matches = new ArrayList<>();
        for (Map.Entry<RecordingCatalog.WorkloadKey, WorkloadAccumulator> entry : workloads.entrySet()) {
            if (matchesFilter(entry.getKey(), namespace, pod, container)) {
                matches.add(entry.getValue().snapshot(entry.getKey()));
            }
        }
        matches.sort(Comparator
                .comparing(WorkloadSnapshot::namespace)
                .thenComparing(WorkloadSnapshot::pod)
                .thenComparing(WorkloadSnapshot::container));
        if (blank(namespace) && blank(pod) && blank(container)) {
            return WorkloadDiscoveryDocument.tree(matches);
        }
        if (!blank(namespace) && blank(pod) && blank(container)) {
            return WorkloadDiscoveryDocument.namespaceTree(namespace, matches);
        }
        if (!blank(namespace) && !blank(pod) && blank(container)) {
            return WorkloadDiscoveryDocument.podTree(namespace, pod, matches);
        }
        return WorkloadDiscoveryDocument.tree(matches);
    }

    public WorkloadSnapshot snapshot(RecordingCatalog.WorkloadKey key) {
        WorkloadAccumulator accumulator = workloads.get(key);
        return accumulator == null ? null : accumulator.snapshot(key);
    }

    private static boolean matchesFilter(
            RecordingCatalog.WorkloadKey key, String namespace, String pod, String container) {
        if (!blank(namespace) && !namespace.equals(key.namespace())) {
            return false;
        }
        if (!blank(pod) && !pod.equals(key.pod())) {
            return false;
        }
        if (!blank(container) && !container.equals(key.container())) {
            return false;
        }
        return true;
    }

    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class WorkloadAccumulator {
        private final java.util.Set<String> recordingIds = ConcurrentHashMap.newKeySet();
        private int chunks;
        private long minStartNs = Long.MAX_VALUE;
        private long maxEndNs = Long.MIN_VALUE;
        private volatile String jvmVersion;

        void add(ChunkMetadata metadata, Path payload) {
            recordingIds.add(metadata.recordingId());
            chunks++;
            if (metadata.chunkStartTimeNs() > 0) {
                minStartNs = Math.min(minStartNs, metadata.chunkStartTimeNs());
                long end = metadata.chunkDurationNs() > 0
                        ? metadata.chunkStartTimeNs() + metadata.chunkDurationNs()
                        : metadata.chunkStartTimeNs();
                maxEndNs = Math.max(maxEndNs, end);
            }
            if (jvmVersion == null && metadata.chunkOffset() == 0 && payload != null) {
                String probed = JfrJvmVersionProbe.probe(payload);
                if (probed != null) {
                    jvmVersion = probed;
                }
            }
        }

        WorkloadSnapshot snapshot(RecordingCatalog.WorkloadKey key) {
            return new WorkloadSnapshot(
                    key.namespace(),
                    key.pod(),
                    key.container(),
                    recordingIds.size(),
                    chunks,
                    minStartNs == Long.MAX_VALUE ? null : iso(minStartNs),
                    maxEndNs == Long.MIN_VALUE ? null : iso(maxEndNs),
                    jvmVersion);
        }
    }

    private static String iso(long epochNs) {
        long seconds = epochNs / 1_000_000_000L;
        int nanos = (int) (epochNs % 1_000_000_000L);
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkloadSnapshot(
            String namespace,
            String pod,
            String container,
            int recordingCount,
            int chunkCount,
            String start,
            String end,
            String jvmVersion) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContainerIndex(
            int recordingCount, int chunkCount, String start, String end, String jvmVersion) {

        static ContainerIndex from(WorkloadSnapshot snapshot) {
            return new ContainerIndex(
                    snapshot.recordingCount(),
                    snapshot.chunkCount(),
                    snapshot.start(),
                    snapshot.end(),
                    snapshot.jvmVersion());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodDiscovery(Map<String, ContainerIndex> containers) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NamespaceDiscovery(Map<String, PodDiscovery> pods) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkloadDiscoveryDocument(
            String namespace,
            String pod,
            Map<String, NamespaceDiscovery> namespaces,
            Map<String, PodDiscovery> pods,
            Map<String, ContainerIndex> containers,
            List<WorkloadSnapshot> workloads) {

        static WorkloadDiscoveryDocument tree(List<WorkloadSnapshot> matches) {
            Map<String, NamespaceDiscovery> tree = new LinkedHashMap<>();
            for (WorkloadSnapshot snapshot : matches) {
                NamespaceDiscovery namespace = tree.computeIfAbsent(
                        snapshot.namespace(), ignored -> new NamespaceDiscovery(new LinkedHashMap<>()));
                PodDiscovery pod = namespace.pods().computeIfAbsent(
                        snapshot.pod(), ignored -> new PodDiscovery(new LinkedHashMap<>()));
                pod.containers().put(snapshot.container(), ContainerIndex.from(snapshot));
            }
            return new WorkloadDiscoveryDocument(null, null, tree, null, null, null);
        }

        static WorkloadDiscoveryDocument namespaceTree(String namespace, List<WorkloadSnapshot> matches) {
            Map<String, PodDiscovery> pods = new LinkedHashMap<>();
            for (WorkloadSnapshot snapshot : matches) {
                pods.computeIfAbsent(snapshot.pod(), ignored -> new PodDiscovery(new LinkedHashMap<>()))
                        .containers()
                        .put(snapshot.container(), ContainerIndex.from(snapshot));
            }
            return new WorkloadDiscoveryDocument(namespace, null, null, pods, null, null);
        }

        static WorkloadDiscoveryDocument podTree(String namespace, String pod, List<WorkloadSnapshot> matches) {
            Map<String, ContainerIndex> containers = new LinkedHashMap<>();
            for (WorkloadSnapshot snapshot : matches) {
                containers.put(snapshot.container(), ContainerIndex.from(snapshot));
            }
            return new WorkloadDiscoveryDocument(namespace, pod, null, null, containers, null);
        }

        static WorkloadDiscoveryDocument flat(List<WorkloadSnapshot> matches) {
            return new WorkloadDiscoveryDocument(null, null, null, null, null, matches);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkloadDetailDocument(
            String namespace,
            String pod,
            String container,
            int recordingCount,
            int chunkCount,
            String start,
            String end,
            String jvmVersion,
            List<RecordingCatalog.RecordingSummary> recordings) {}
}
