package io.jafra.analyzer.api.v2;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.jafra.analyzer.ingest.decode.DuckConnections;

/** Read-only DuckDB connection for /v2. Writes stay on the ingest catalog. */
@ApplicationScoped
public class V2Database {
    private final Path root;
    private final Connection connection;

    @Inject
    public V2Database(
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root,
            @ConfigProperty(name = "jafra.duckdb.memory-limit", defaultValue = "256MB") String memoryLimit,
            @ConfigProperty(name = "jafra.duckdb.threads", defaultValue = "1") int duckThreads) throws SQLException {
        DuckConnections.configure(memoryLimit, duckThreads);
        this.root = Path.of(root).resolve("v2");
        this.connection = DuckConnections.open("jdbc:duckdb:");
    }

    public Path root() {
        return root;
    }

    public Connection connection() {
        return connection;
    }
}
