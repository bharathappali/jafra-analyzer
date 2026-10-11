package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

/** Best-effort JVM version from a single chunk file (typically offset 0). */
final class JfrJvmVersionProbe {
    private JfrJvmVersionProbe() {}

    static String probe(Path chunk) {
        if (chunk == null) {
            return null;
        }
        try {
            IItemCollection events = JfrChunkFiles.load(List.of(chunk));
            IItemCollection typed = events.apply(ItemFilters.type("jdk.JVMInformation"));
            for (IItemIterable iterable : typed) {
                for (IItem item : iterable) {
                    String version = JfrItemAccess.text(item, "jvmVersion", "javaVersion");
                    if (version != null && !version.isBlank()) {
                        return version;
                    }
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
}
