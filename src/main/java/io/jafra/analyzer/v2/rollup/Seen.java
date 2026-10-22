package io.jafra.analyzer.v2.rollup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Events copied out of one chunk. Safe to keep after the RecordingFile moves on. */
public final class Seen {
    public final String type;
    public final long startMs;
    public final long endMs;
    public final String thread;
    public final Map<String, Object> values;
    public final List<Frame> frames;

    public Seen(String type, long startMs, long endMs, String thread, Map<String, Object> values, List<Frame> frames) {
        this.type = type;
        this.startMs = startMs;
        this.endMs = endMs;
        this.thread = thread == null ? "" : thread;
        this.values = values;
        this.frames = frames;
    }

    public Double num(String field) {
        Object value = values.get(field);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    public String text(String field) {
        Object value = values.get(field);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    public long id(String field) {
        Double value = num(field);
        if (value == null) {
            return -1L;
        }
        return value.longValue();
    }
}
