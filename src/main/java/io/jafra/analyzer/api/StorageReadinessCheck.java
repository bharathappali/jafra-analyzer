package io.jafra.analyzer.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import io.jafra.analyzer.storage.ChunkStore;

/** Readiness is down while a legacy migration is still running. Liveness is a separate check. */
@Readiness
@ApplicationScoped
public class StorageReadinessCheck implements HealthCheck {
    private final ChunkStore store;

    @Inject
    public StorageReadinessCheck(ChunkStore store) {
        this.store = store;
    }

    @Override
    public HealthCheckResponse call() {
        if (store.ready()) {
            return HealthCheckResponse.named("storage-migration").up().build();
        }
        return HealthCheckResponse.named("storage-migration").down().build();
    }
}
