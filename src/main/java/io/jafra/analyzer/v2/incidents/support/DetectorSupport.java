package io.jafra.analyzer.v2.incidents.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.jafra.analyzer.v2.incidents.Band;
import io.jafra.analyzer.v2.incidents.Detector;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle;
import io.jafra.analyzer.v2.incidents.WindowInput;
import io.jafra.analyzer.v2.incidents.WindowVerdict;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.rollup.Frames;

/** Shared JDK gate, caps, score, band text, pod-limit headroom, and frame helpers. */
public final class DetectorSupport {
    private DetectorSupport() {}

    public static WindowVerdict jdkGate(Detector detector, WindowInput in) {
        int jdk = in == null || in.settings() == null ? 0 : in.settings().jdk();
        if (detector.jdks().contains(jdk)) {
            return null;
        }
        return WindowVerdict.unknown("needs JDK " + detector.jdks(), Set.of("not_on_this_jdk"));
    }

    public static String band(String label) {
        return "band " + label + " (proposal, pending KUSMA)";
    }

    public static double score(double value, Band band) {
        return IncidentLifecycle.score(value, band);
    }

    public static List<String> todos(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.size() <= 3 ? List.copyOf(lines) : List.copyOf(lines.subList(0, 3));
    }

    /**
     * Minutes until usage reaches the pod limit at {@code slopePerHour}. Null minutes when the pod
     * limit is missing or the slope is not positive; {@code reason} says why.
     */
    public static Headroom headroom(Double limitBytes, Double usageBytes, double slopePerHour) {
        if (limitBytes == null || limitBytes <= 0d) {
            return new Headroom(null, "no pod memory limit");
        }
        if (usageBytes == null || !(slopePerHour > 0d)) {
            return new Headroom(null, "no growth slope");
        }
        double remaining = limitBytes - usageBytes;
        return new Headroom(remaining / (slopePerHour / 60d), null);
    }

    public static String appFrame(List<Frame> stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String caller = Frames.appCaller(stack, 0);
        if (caller != null && !caller.isBlank()) {
            return caller;
        }
        String name = stack.get(0).name();
        return name == null ? "" : name;
    }

    public static boolean stackContains(List<Frame> stack, List<String> patterns) {
        if (stack == null || patterns == null) {
            return false;
        }
        for (Frame frame : stack) {
            String name = frame.name();
            if (name == null) {
                continue;
            }
            for (String pattern : patterns) {
                if (pattern != null && !pattern.isBlank() && name.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<String> sharesByTopFrame(List<Frame> frames) {
        List<String> names = new ArrayList<>();
        if (frames == null) {
            return names;
        }
        for (Frame frame : frames) {
            if (frame.name() != null && !frame.name().isBlank()) {
                names.add(frame.name());
            }
            if (names.size() == 5) {
                break;
            }
        }
        return names;
    }

    public record Headroom(Double minutes, String reason) {}
}
