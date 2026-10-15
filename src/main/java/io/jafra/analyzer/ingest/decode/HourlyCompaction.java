package io.jafra.analyzer.ingest.decode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges per-chunk Parquet files whose modification time is older than the minimum age into one
 * file per hour of their row timestamps. A chunk that crosses an hour boundary contributes its
 * rows to each hour, and the chunk file is removed only after every hour file is written.
 */
public final class HourlyCompaction {
    private static final long HOUR_NS = 3_600_000_000_000L;
    private static final DateTimeFormatter HOUR_NAME = DateTimeFormatter.ofPattern("'hour-'yyyyMMddHH").withZone(ZoneOffset.UTC);

    private HourlyCompaction() {}

    public static int compact(Connection connection, Path v2Root, Instant now, Duration minAge) throws SQLException, IOException {
        if (!Files.isDirectory(v2Root)) {
            return 0;
        }
        Instant cutoff = now.minus(minAge);
        List<Path> eligible = new ArrayList<>();
        try (var walk = Files.walk(v2Root)) {
            walk.filter(Files::isRegularFile).filter(HourlyCompaction::chunkFile).forEach(eligible::add);
        }
        Map<Group, List<Path>> groups = new LinkedHashMap<>();
        List<Path> empty = new ArrayList<>();
        for (Path file : eligible) {
            if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                continue;
            }
            List<Long> hours = hours(connection, file);
            if (hours.isEmpty()) {
                empty.add(file);
                continue;
            }
            for (Long hour : hours) {
                groups.computeIfAbsent(new Group(file.getParent(), hour), ignored -> new ArrayList<>()).add(file);
            }
        }
        List<Published> published = new ArrayList<>();
        try {
            for (var entry : groups.entrySet()) {
                published.add(writeHour(connection, entry.getKey(), entry.getValue()));
            }
        } catch (SQLException | IOException error) {
            for (Published item : published) {
                Files.deleteIfExists(item.partial());
            }
            throw error;
        }
        int removed = 0;
        for (Published item : published) {
            Files.move(item.partial(), item.hourFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        for (Path file : eligible) {
            if (empty.contains(file) || groups.values().stream().anyMatch(files -> files.contains(file))) {
                Files.deleteIfExists(file);
                removed++;
            }
        }
        return removed;
    }

    private static Published writeHour(Connection connection, Group group, List<Path> chunkFiles) throws SQLException, IOException {
        Path hourFile = group.dir().resolve(HOUR_NAME.format(Instant.ofEpochSecond(group.hourNs() / 1_000_000_000L)) + ".parquet");
        Path partial = group.dir().resolve(hourFile.getFileName().toString() + ".partial");
        List<String> inputs = new ArrayList<>();
        if (Files.exists(hourFile)) {
            inputs.add(quote(hourFile));
        }
        long end = group.hourNs() + HOUR_NS;
        StringBuilder union = new StringBuilder();
        for (Path chunk : chunkFiles) {
            if (!union.isEmpty()) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT * FROM read_parquet('")
                    .append(quote(chunk))
                    .append("', hive_partitioning=false) WHERE t >= ")
                    .append(group.hourNs())
                    .append(" AND t < ")
                    .append(end);
        }
        String from = inputs.isEmpty()
                ? union.toString()
                : "SELECT * FROM read_parquet([" + String.join(", ", inputs.stream().map(path -> "'" + path + "'").toList())
                        + "], hive_partitioning=false) UNION ALL " + union;
        String projection = distinct(group.dir()) ? "SELECT DISTINCT *" : "SELECT *";
        try (Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + projection + " FROM (" + from + ")) TO '"
                    + quote(partial) + "' (FORMAT parquet, COMPRESSION zstd)");
        }
        return new Published(partial, hourFile);
    }

    private static boolean distinct(Path dir) {
        String table = tableName(dir);
        return "stack".equals(table) || "frame".equals(table);
    }

    private static String tableName(Path dir) {
        Path current = dir;
        while (current != null && current.getParent() != null) {
            if ("v2".equals(current.getParent().getFileName().toString())) {
                return current.getFileName().toString();
            }
            current = current.getParent();
        }
        return "";
    }

    private static List<Long> hours(Connection connection, Path file) throws SQLException {
        List<Long> hours = new ArrayList<>();
        String sql = "SELECT DISTINCT (t // " + HOUR_NS + ") * " + HOUR_NS
                + " AS hour FROM read_parquet('" + quote(file) + "', hive_partitioning=false) WHERE t IS NOT NULL";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                hours.add(rows.getLong(1));
            }
        }
        return hours;
    }

    private static boolean chunkFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".parquet") && !name.startsWith("hour-") && !name.endsWith(".partial");
    }

    private static String quote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private record Group(Path dir, long hourNs) {}

    private record Published(Path partial, Path hourFile) {}
}
