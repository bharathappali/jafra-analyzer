package io.jafra.analyzer.ingest.decode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens DuckDB with a bounded memory limit and a single thread. */
public final class DuckConnections {
    public static volatile String memoryLimit = "256MB";
    public static volatile int threads = 1;

    private DuckConnections() {}

    public static void configure(String memoryLimit, int threads) {
        if (memoryLimit != null && !memoryLimit.isBlank()) {
            DuckConnections.memoryLimit = memoryLimit.trim();
        }
        DuckConnections.threads = Math.max(1, threads);
    }

    public static Connection open(String url) throws SQLException {
        IngestCatalog.ensureDriver();
        Connection connection = DriverManager.getConnection(url);
        String limit = memoryLimit.replace("'", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET memory_limit='" + limit + "'");
            statement.execute("SET threads=" + threads);
        }
        return connection;
    }
}
