package io.jafra.analyzer.ingest.decode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Moves Parquet that was written under the wrong jvm_instance_id onto the id just learned from JVMInformation. */
final class InstanceFiles {
    private InstanceFiles() {}

    static void relocate(Connection connection, Path v2Root, String namespace, String pod, String container,
            IngestCatalog.Claim claim, String toId, long startNs) throws SQLException, IOException {
        if (v2Root == null || !Files.isDirectory(v2Root)) {
            return;
        }
        try (var tables = Files.list(v2Root)) {
            for (Path table : tables.filter(Files::isDirectory).toList()) {
                Path source = partition(table, namespace, pod, container, claim.fromId());
                if (!Files.isDirectory(source)) {
                    continue;
                }
                boolean aggregate = aggregate(table.getFileName().toString());
                List<Path> files;
                try (var walk = Files.walk(source)) {
                    files = walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".parquet"))
                            .toList();
                }
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    Path dest = destination(table, namespace, pod, container, toId, source, file);
                    if (claim.emptied()) {
                        rewrite(connection, file, dest, toId, null, true);
                        continue;
                    }
                    if (aggregate) {
                        Long bucket = bucketOf(name);
                        if (bucket != null && bucket >= floor(claim.minStartNs()) && bucket < ceil(claim.maxEndNs())) {
                            Files.deleteIfExists(file);
                        }
                        continue;
                    }
                    if (name.startsWith("hour-")) {
                        split(connection, file, dest, toId, startNs);
                        continue;
                    }
                    if (claimedChunk(name, claim.chunkIds())) {
                        rewrite(connection, file, dest, toId, null, true);
                    }
                }
                deleteEmpty(source);
            }
        }
    }

    private static void split(Connection connection, Path source, Path dest, String toId, long startNs)
            throws SQLException, IOException {
        long after = count(connection, source, "t >= " + startNs);
        if (after == 0L) {
            return;
        }
        long before = count(connection, source, "t < " + startNs + " OR t IS NULL");
        rewrite(connection, source, dest, toId, "t >= " + startNs, false);
        if (before == 0L) {
            Files.deleteIfExists(source);
            return;
        }
        rewrite(connection, source, source, null, "t < " + startNs + " OR t IS NULL", false);
    }

    private static void rewrite(Connection connection, Path source, Path dest, String toId, String where, boolean deleteSource)
            throws SQLException, IOException {
        if (!Files.exists(source)) {
            return;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".rewriting");
        String projection = toId == null
                ? "SELECT * FROM read_parquet('" + quote(source) + "')"
                : "SELECT * REPLACE ('" + escape(toId) + "' AS jvm_instance_id) FROM read_parquet('" + quote(source) + "')";
        if (where != null) {
            projection += " WHERE " + where;
        }
        if (toId != null && Files.exists(dest)) {
            projection = projection + " UNION ALL BY NAME SELECT * FROM read_parquet('" + quote(dest) + "')";
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + projection + ") TO '" + quote(tmp) + "' (FORMAT parquet, COMPRESSION zstd)");
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (deleteSource && !source.equals(dest)) {
            Files.deleteIfExists(source);
        }
    }

    private static long count(Connection connection, Path source, String where) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM read_parquet('" + quote(source) + "') WHERE " + where)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static boolean claimedChunk(String name, List<String> chunkIds) {
        for (String chunkId : chunkIds) {
            if (name.equals(chunkId + ".parquet") || name.startsWith(chunkId + "-")) {
                return true;
            }
        }
        return false;
    }

    private static Long bucketOf(String name) {
        String stem = name.substring(0, name.length() - ".parquet".length());
        int dash = stem.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String prefix = stem.substring(0, dash);
        if (!"bucket".equals(prefix) && !"top".equals(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(stem.substring(dash + 1));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean aggregate(String table) {
        return table.startsWith("rollup_") || "top_1m".equals(table);
    }

    private static long floor(long t) {
        return Math.floorDiv(t, RollupWriter.MINUTE_NS) * RollupWriter.MINUTE_NS;
    }

    private static long ceil(long t) {
        return floor(t) + RollupWriter.MINUTE_NS;
    }

    private static Path partition(Path table, String namespace, String pod, String container, String instanceId) {
        return table.resolve("namespace=" + ParquetTables.sanitize(namespace))
                .resolve("pod=" + ParquetTables.sanitize(pod))
                .resolve("container=" + ParquetTables.sanitize(container))
                .resolve("jvm_instance_id=" + ParquetTables.sanitize(instanceId));
    }

    private static Path destination(Path table, String namespace, String pod, String container, String toId, Path source, Path file) {
        return partition(table, namespace, pod, container, toId).resolve(source.relativize(file));
    }

    private static void deleteEmpty(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.reverseOrder());
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var children = Files.list(dir)) {
                if (children.findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }
        }
    }

    private static String quote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
