package io.jafra.analyzer.recordings;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisEnvelope(
        String namespace,
        String pod,
        String container,
        String recording,
        List<String> recordings,
        String start,
        String end,
        String from,
        String to,
        long bytes,
        int chunks,
        String generatedAt) {

    static AnalysisEnvelope from(RecordingCatalog.WindowSelection selection) {
        List<String> files = selection.recordings().stream()
                .map(item -> item.summary().filename())
                .toList();
        return new AnalysisEnvelope(
                selection.key().namespace(),
                selection.key().pod(),
                selection.key().container(),
                String.join(",", files),
                files,
                iso(selection.start()),
                iso(selection.stop()),
                iso(selection.from()),
                iso(selection.to()),
                selection.bytes(),
                selection.chunks(),
                Instant.now().toString());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
