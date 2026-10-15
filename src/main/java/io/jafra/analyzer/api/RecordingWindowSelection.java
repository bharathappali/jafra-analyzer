package io.jafra.analyzer.api;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.jafra.analyzer.recordings.RecordingCatalog;
import io.jafra.analyzer.recordings.ReportWindow;

@ApplicationScoped
public class RecordingWindowSelection {
    @Inject
    RecordingCatalog catalog;

    public <T> T withSelection(
            String namespace,
            String pod,
            String container,
            String recording,
            String last,
            String from,
            String to,
            String before,
            String after,
            Function<RecordingCatalog.WindowSelection, T> action) {
        validateWorkloadParams(namespace, pod, container);
        ReportWindow window;
        try {
            window = ReportWindow.parse(Instant.now(), last, from, to, before, after);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
        if (window != null && recording != null && !recording.isBlank()) {
            throw badRequest("recording and time window cannot be combined");
        }
        RecordingCatalog.WindowSelection selected;
        try {
            if (window != null) {
                Optional<RecordingCatalog.WindowSelection> opened = catalog.openWindow(namespace, pod, container, window);
                if (opened.isEmpty()) {
                    throw missingWindow(namespace, pod, container, window);
                }
                selected = opened.get();
            } else {
                RecordingCatalog.IndexedRecording file = recording == null || recording.isBlank()
                        ? catalog.latestClosed(namespace, pod, container)
                        : catalog.requireRecording(namespace, pod, container, recording);
                selected = catalog.singleFile(file);
            }
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        } catch (IOException error) {
            throw unavailable(namespace, pod, container, recording == null ? "window" : recording, error.getMessage());
        }
        return action.apply(selected);
    }

    /**
     * Timeseries APIs: only {@code last} / {@code from} / {@code to} / {@code before} / {@code after}.
     * Defaults to {@link ReportWindow#DEFAULT_TIMESERIES_LAST} when none are set. {@code recording} is rejected.
     */
    public <T> T withTimeSeries(
            String namespace,
            String pod,
            String container,
            String recording,
            String last,
            String from,
            String to,
            String before,
            String after,
            Function<RecordingCatalog.WindowSelection, T> action) {
        validateWorkloadParams(namespace, pod, container);
        if (recording != null && !recording.isBlank()) {
            throw badRequest("recording is not supported; use last, from/to, before, or after");
        }
        ReportWindow window;
        try {
            window = ReportWindow.parseTimeseries(Instant.now(), last, from, to, before, after);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
        RecordingCatalog.WindowSelection selected;
        try {
            Optional<RecordingCatalog.WindowSelection> opened = catalog.openChunkWindow(namespace, pod, container, window);
            if (opened.isEmpty()) {
                throw missingWindow(namespace, pod, container, window);
            }
            selected = opened.get();
        } catch (NotFoundException error) {
            throw new WebApplicationException(
                    Response.status(404)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("status", "not_found", "error", error.getMessage()))
                            .build());
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        } catch (IOException error) {
            throw unavailable(namespace, pod, container, "window", error.getMessage());
        }
        return action.apply(selected);
    }

    public RecordingCatalog.WorkloadRecordings requireWorkload(String namespace, String pod, String container) {
        return catalog.requireWorkload(namespace, pod, container);
    }

    static void validateWorkloadParams(String namespace, String pod, String container) {
        if (namespace == null || namespace.isBlank()) {
            throw badRequest("namespace is required");
        }
        if (pod == null || pod.isBlank()) {
            throw badRequest("pod is required");
        }
        if (container == null || container.isBlank()) {
            throw badRequest("container is required");
        }
    }

    static WebApplicationException missingWindow(String namespace, String pod, String container, ReportWindow window) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "not_found");
        body.put("namespace", namespace);
        body.put("pod", pod);
        body.put("container", container);
        if (window.from() != null) {
            body.put("from", window.from().toString());
        }
        if (window.to() != null) {
            body.put("to", window.to().toString());
        }
        body.put("error", "no recordings overlap the requested window");
        return new WebApplicationException(
                Response.status(404).type(MediaType.APPLICATION_JSON).entity(body).build());
    }

    public static WebApplicationException badRequest(String error) {
        return new WebApplicationException(
                Response.status(400)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("status", "bad_request", "error", error))
                        .build());
    }

    static WebApplicationException unavailable(RecordingCatalog.WindowSelection selection, String error) {
        return unavailable(
                selection.key().namespace(),
                selection.key().pod(),
                selection.key().container(),
                String.join(",", selection.recordings().stream().map(item -> item.summary().filename()).toList()),
                error);
    }

    static WebApplicationException unavailable(
            String namespace, String pod, String container, String recording, String error) {
        return new WebApplicationException(
                Response.status(422)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of(
                                "status", "unavailable",
                                "namespace", namespace,
                                "pod", pod,
                                "container", container,
                                "recording", recording,
                                "error", error))
                        .build());
    }
}
