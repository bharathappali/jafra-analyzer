package io.jafra.analyzer.v2.api;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;

@ApplicationScoped
public class JvmResolver {
    private final V2Store store;

    @Inject
    public JvmResolver(V2Store store) {
        this.store = store;
    }

    public JvmRow resolve(String namespace, String pod, String container, String cluster, String start) {
        List<JvmRow> rows;
        try {
            rows = store.findJvms(namespace, pod, container, cluster);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        if (rows.isEmpty()) {
            throw V2Exception.unknownJvm();
        }
        if (cluster == null || cluster.isBlank()) {
            Set<String> clusters = new LinkedHashSet<>();
            for (JvmRow row : rows) {
                clusters.add(row.clusterId());
            }
            if (clusters.size() > 1) {
                throw V2Exception.ambiguous(new ArrayList<>(clusters));
            }
        }
        if (start != null && !start.isBlank()) {
            long wanted = V2Times.parse(start, "start").toEpochMilli();
            for (JvmRow row : rows) {
                if (row.jvmStartMs() == wanted) {
                    return row;
                }
            }
            throw V2Exception.unknownJvm();
        }
        JvmRow latest = rows.get(0);
        for (JvmRow row : rows) {
            if (row.jvmStartMs() >= latest.jvmStartMs()) {
                latest = row;
            }
        }
        return latest;
    }

    public Instant newest(JvmRow jvm) {
        long end = jvm.lastMs() > 0 ? jvm.lastMs() : jvm.jvmStartMs();
        return Instant.ofEpochMilli(end);
    }
}
