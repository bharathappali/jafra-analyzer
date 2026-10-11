package io.jafra.analyzer.recordings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maps API type names to JFR type ids present in a recording. */
public final class JfrTypeResolver {
    private JfrTypeResolver() {}

    public static List<String> resolve(List<String> requested, Set<String> available) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("types is required");
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String raw : requested) {
            String token = raw == null ? "" : raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            String match = resolveOne(token, available);
            if (match == null) {
                missing.add(token);
            } else {
                resolved.add(match);
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("no requested types are present in the recording: " + missing);
        }
        if (!missing.isEmpty()) {
            // Allow partial collect when at least one type matched.
        }
        return List.copyOf(resolved);
    }

    private static String resolveOne(String token, Set<String> available) {
        if (available.contains(token)) {
            return token;
        }
        String simple = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
        String jdk = "jdk." + simple;
        if (available.contains(jdk)) {
            return jdk;
        }
        String profiler = "profiler." + simple;
        if (available.contains(profiler)) {
            return profiler;
        }
        for (String id : available) {
            String suffix = id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : id;
            if (suffix.equalsIgnoreCase(simple)) {
                return id;
            }
        }
        if (simple.endsWith("Flag") || simple.endsWith("FlagChanged")) {
            for (String id : available) {
                if (id.toLowerCase(Locale.ROOT).endsWith("." + simple.toLowerCase(Locale.ROOT))) {
                    return id;
                }
            }
        }
        if (simple.startsWith("BiasedLockRevocation")) {
            for (String id : available) {
                if (id.contains("BiasedLockRevocation")) {
                    return id;
                }
            }
        }
        return null;
    }

    public static List<String> splitTypesParam(String types) {
        if (types == null || types.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String piece : types.split(",")) {
            if (!piece.isBlank()) {
                parts.add(piece.trim());
            }
        }
        return parts;
    }
}
