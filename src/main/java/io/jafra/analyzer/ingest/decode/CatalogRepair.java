package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import io.jafra.analyzer.storage.ChunkStore;

/** Fills identity columns left empty by older catalogs and requeues chunks whose Parquet cannot be read. */
final class CatalogRepair {
    private static final Logger LOG = Logger.getLogger(CatalogRepair.class);

    private CatalogRepair() {}

    static void repair(IngestCatalog catalog, ChunkStore store, Path v2Root) {
        try {
            for (String chunkId : catalog.chunksMissingIdentity()) {
                catalog.fillIdentity(chunkId, store.metadata(chunkId));
            }
            catalog.assignLanes();
            if (v2Root == null || !Files.isDirectory(v2Root)) {
                return;
            }
            IngestCatalog.ensureDriver();
            List<String> unreadable = new ArrayList<>();
            try (Connection reader = DriverManager.getConnection("jdbc:duckdb:");
                    var walk = Files.walk(v2Root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".parquet") || name.startsWith("hour-") || name.startsWith("bucket-")) {
                        continue;
                    }
                    if (!readable(reader, file)) {
                        unreadable.add(name.substring(0, name.length() - ".parquet".length()));
                    }
                }
            }
            for (String chunkId : unreadable) {
                deleteChunkFiles(v2Root, chunkId);
                catalog.reopen(chunkId);
                LOG.warnf("unreadable parquet for %s; chunk returned to pending", chunkId);
            }
        } catch (Exception error) {
            LOG.warn("v2 catalog repair failed", error);
        }
    }

    private static boolean readable(Connection connection, Path file) {
        String path = file.toAbsolutePath().toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT count(*) FROM read_parquet('" + path + "')");
            return true;
        } catch (SQLException error) {
            return false;
        }
    }

    private static void deleteChunkFiles(Path v2Root, String chunkId) throws java.io.IOException {
        String parquet = chunkId + ".parquet";
        String temp = parquet + ".tmp";
        try (var walk = Files.walk(v2Root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (parquet.equals(name) || temp.equals(name)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
