package io.jafra.analyzer.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Tombstones and summary-only marks. The read path and the sweep share this file. */
public final class RetentionLog {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RetentionLog() {}

    public static synchronized List<Entry> read(Path v2) {
        Path file = v2.resolve("retention.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            File body = JSON.readValue(file.toFile(), File.class);
            return body.entries == null ? List.of() : List.copyOf(body.entries);
        } catch (IOException error) {
            return List.of();
        }
    }

    public static synchronized void upsert(Path v2, Entry entry) throws IOException {
        List<Entry> next = new ArrayList<>();
        for (Entry existing : read(v2)) {
            if (!same(existing, entry)) {
                next.add(existing);
            }
        }
        next.add(entry);
        File body = new File();
        body.entries = next;
        Files.createDirectories(v2);
        Path tmp = v2.resolve("retention.json.tmp");
        JSON.writeValue(tmp.toFile(), body);
        Files.move(tmp, v2.resolve("retention.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String state(Path v2, String ns, String pod, String container, String instance) {
        Entry entry = find(v2, ns, pod, container, instance);
        return entry == null ? null : entry.state;
    }

    public static Entry find(Path v2, String ns, String pod, String container, String instance) {
        for (Entry entry : read(v2)) {
            if (ns.equals(entry.ns) && pod.equals(entry.pod) && container.equals(entry.container) && instance.equals(entry.jvm_instance_id)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean same(Entry left, Entry right) {
        return left.ns.equals(right.ns) && left.pod.equals(right.pod) && left.container.equals(right.container)
                && left.jvm_instance_id.equals(right.jvm_instance_id);
    }

    public static final class File {
        public List<Entry> entries = new ArrayList<>();
    }

    public static final class Entry {
        public String ns;
        public String pod;
        public String container;
        public String jvm_instance_id;
        public String state;
        public long at;
        public long purge_at;
        public long raw;
        public long detail;
        public long kept;

        public Entry() {}

        public Entry(String ns, String pod, String container, String instance, String state, long at, long purgeAt, long raw,
                long detail, long kept) {
            this.ns = ns;
            this.pod = pod;
            this.container = container;
            this.jvm_instance_id = instance;
            this.state = state;
            this.at = at;
            this.purge_at = purgeAt;
            this.raw = raw;
            this.detail = detail;
            this.kept = kept;
        }
    }
}
