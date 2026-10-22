package io.jafra.analyzer.v2.frames;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.FrameKindSpec;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.rollup.Frames;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

@ApplicationScoped
public class FrameService {
    static final int TOP_FRAMES = 15;
    static final int FOCUS_PATHS = 5;
    static final int FOCUS_FRAMES = 8;
    static final int LOW_CONFIDENCE = 200;
    private static final double PRUNE = 0.005;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final Catalogue catalogue;

    @Inject
    public FrameService(V2Store store, Catalogue catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    /**
     * Allocation site for one stack, callee first. Uses the leaf when its category is already
     * {@code app}; otherwise {@link Frames#appCaller} from the leaf; otherwise the leaf name.
     */
    public static String allocationSite(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        Frame leaf = frames.get(0);
        if (leaf != null && "app".equals(leaf.category())) {
            return leaf.name();
        }
        String caller = Frames.appCaller(frames, 0);
        if (caller != null && !caller.isBlank()) {
            return caller;
        }
        return leaf == null ? null : leaf.name();
    }

    public Flame flame(
            JvmRow jvm, String group, String kindParam, String thread, String state, String handler, String last, String from, String to) {
        String kind = flameKind(jvm, group, kindParam);
        rejectState(kind, state);
        V2Range range = range(jvm, last, from, to);
        try {
            List<Weighted> stacks = stacks(jvm, kind, range, thread, state, handler);
            Node root = new Node("all", "");
            long n = 0;
            for (Weighted stack : stacks) {
                n += stack.count;
                root.total += stack.weight;
                Node cursor = root;
                int depth = 0;
                for (int i = stack.frames.size() - 1; i >= 0 && depth < Frames.MAX_DEPTH; i--) {
                    Frame frame = stack.frames.get(i);
                    cursor = cursor.child(frame.name(), frame.category());
                    cursor.total += stack.weight;
                    depth++;
                }
                cursor.self += stack.weight;
            }
            double floor = root.total * PRUNE;
            return new Flame(kind, window(range), n, flags(n), toNode(root, floor));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public TopFrames top(
            JvmRow jvm,
            String kind,
            String thread,
            String state,
            String endpoint,
            String focus,
            String last,
            String from,
            String to,
            String vsFrom,
            String vsTo) {
        FrameKindSpec spec = requireKind(jvm, kind);
        rejectState(spec.kind(), state);
        if (endpoint != null && !endpoint.isBlank() && jvm.jdkMajor() < 25) {
            throw V2Exception.notOnJdk("needs JDK 25");
        }
        V2Range range = range(jvm, last, from, to);
        try {
            List<Weighted> stacks = stacks(jvm, spec.kind(), range, thread, state, blank(endpoint));
            long n = stacks.stream().mapToLong(stack -> stack.count).sum();
            List<String> flags = flags(n);
            if (focus != null && !focus.isBlank()) {
                return new TopFrames(spec.kind(), window(range), n, flags, List.of(), paths(stacks, focus), null);
            }
            List<FrameRow> rows = rank(stacks);
            Boolean significant = null;
            if (vsFrom != null || vsTo != null) {
                if (vsFrom == null || vsTo == null) {
                    throw V2Exception.badRange("vs_from and vs_to are set together");
                }
                V2Range other = V2Range.parse(null, vsFrom, vsTo, Instant.ofEpochMilli(jvm.lastMs()));
                List<Weighted> otherStacks = stacks(jvm, spec.kind(), other, thread, state, blank(endpoint));
                long otherN = otherStacks.stream().mapToLong(stack -> stack.count).sum();
                rows = compare(rows, n, rank(otherStacks), otherN);
                significant = rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.significant()));
                if (otherN < LOW_CONFIDENCE) {
                    flags = new ArrayList<>(flags);
                    if (!flags.contains("low_confidence")) {
                        flags.add("low_confidence");
                    }
                }
            }
            return new TopFrames(spec.kind(), window(range), n, flags, rows, List.of(), significant);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static boolean significant(long n1, double p1, long n2, double p2) {
        if (n1 < LOW_CONFIDENCE || n2 < LOW_CONFIDENCE) {
            return false;
        }
        double[] a = wilson(p1, n1);
        double[] b = wilson(p2, n2);
        return a[1] < b[0] || b[1] < a[0];
    }

    static double[] wilson(double p, long n) {
        if (n <= 0) {
            return new double[] {0d, 0d};
        }
        double z = 1.96;
        double z2 = z * z;
        double denom = 1d + z2 / n;
        double center = (p + z2 / (2d * n)) / denom;
        double margin = z * Math.sqrt((p * (1d - p) + z2 / (4d * n)) / n) / denom;
        return new double[] {Math.max(0d, center - margin), Math.min(1d, center + margin)};
    }

    private List<FrameRow> compare(List<FrameRow> left, long nLeft, List<FrameRow> right, long nRight) {
        Map<String, FrameRow> other = new LinkedHashMap<>();
        for (FrameRow row : right) {
            other.put(row.frame(), row);
        }
        List<FrameRow> compared = new ArrayList<>();
        for (FrameRow row : left) {
            FrameRow match = other.get(row.frame());
            double otherShare = match == null ? 0d : match.sharePct();
            long otherCount = match == null ? 0L : match.count();
            boolean sig = significant(
                    nLeft, nLeft == 0 ? 0d : row.count() / (double) nLeft, nRight, nRight == 0 ? 0d : otherCount / (double) nRight);
            compared.add(new FrameRow(
                    row.frame(), row.category(), row.sharePct(), row.appCaller(), row.count(), otherShare, sig));
        }
        return compared;
    }

    private List<FrameRow> rank(List<Weighted> stacks) {
        double total = 0;
        Map<String, Acc> byName = new LinkedHashMap<>();
        for (Weighted stack : stacks) {
            total += stack.weight;
            for (int i = 0; i < stack.frames.size(); i++) {
                Frame frame = stack.frames.get(i);
                Acc acc = byName.computeIfAbsent(frame.name(), ignored -> new Acc(frame.category()));
                acc.weight += stack.weight;
                acc.count += stack.count;
                if (stack.weight >= acc.best) {
                    acc.best = stack.weight;
                    acc.appCaller = Frames.appCaller(stack.frames, i);
                }
            }
        }
        List<Acc> ranked = new ArrayList<>(byName.values());
        List<String> names = new ArrayList<>(byName.keySet());
        names.sort(Comparator.comparingDouble((String name) -> byName.get(name).weight).reversed());
        List<FrameRow> rows = new ArrayList<>();
        double grand = total;
        for (String name : names) {
            if (rows.size() >= TOP_FRAMES) {
                break;
            }
            Acc acc = byName.get(name);
            double share = grand == 0d ? 0d : 100d * acc.weight / grand;
            rows.add(new FrameRow(name, acc.category, share, acc.appCaller, acc.count, null, null));
        }
        ranked.clear();
        return rows;
    }

    private List<PathRow> paths(List<Weighted> stacks, String focus) {
        List<Weighted> matching = new ArrayList<>();
        for (Weighted stack : stacks) {
            if (stack.frames.stream().anyMatch(frame -> focus.equals(frame.name()))) {
                matching.add(stack);
            }
        }
        matching.sort(Comparator.comparingDouble((Weighted stack) -> stack.weight).reversed());
        double total = stacks.stream().mapToDouble(stack -> stack.weight).sum();
        List<PathRow> paths = new ArrayList<>();
        for (Weighted stack : matching) {
            if (paths.size() >= FOCUS_PATHS) {
                break;
            }
            int at = 0;
            for (int i = 0; i < stack.frames.size(); i++) {
                if (focus.equals(stack.frames.get(i).name())) {
                    at = i;
                    break;
                }
            }
            List<FlameNode> frames = new ArrayList<>();
            int end = Math.min(stack.frames.size(), at + FOCUS_FRAMES);
            for (int i = at; i < end; i++) {
                Frame frame = stack.frames.get(i);
                frames.add(new FlameNode(frame.name(), frame.category(), 0d, 0d, List.of()));
            }
            double share = total == 0d ? 0d : 100d * stack.weight / total;
            paths.add(new PathRow(frames, share));
        }
        return paths;
    }

    private List<Weighted> stacks(JvmRow jvm, String kind, V2Range range, String thread, String state, String handler)
            throws IOException {
        List<SampleRow> samples = store.readSamples(
                jvm.jvmId(),
                kind,
                range.from().toEpochMilli(),
                range.to().toEpochMilli(),
                blank(thread),
                blank(state),
                handler);
        Map<Long, String> json = store.readStackJson(samples.stream().map(SampleRow::stackId).distinct().toList());
        Map<Long, Weighted> merged = new LinkedHashMap<>();
        for (SampleRow sample : samples) {
            Weighted weighted = merged.computeIfAbsent(sample.stackId(), id -> new Weighted(parse(json.get(id))));
            weighted.count += sample.count();
            weighted.weight += sample.weight();
        }
        return new ArrayList<>(merged.values());
    }

    private static List<Frame> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = JSON.readTree(json);
            List<Frame> frames = new ArrayList<>();
            if (!array.isArray()) {
                return frames;
            }
            for (JsonNode node : array) {
                frames.add(new Frame(
                        node.path("frame").asText(""),
                        node.path("category").asText(""),
                        node.path("type").asText(""),
                        node.path("line").asInt(0)));
            }
            return frames;
        } catch (IOException error) {
            return List.of();
        }
    }

    private String flameKind(JvmRow jvm, String group, String kindParam) {
        return switch (group) {
            case "cpu" -> "cpu";
            case "alloc" -> "alloc";
            case "locks" -> "lock";
            case "threads" -> "wall";
            case "socket" -> "socket";
            case "endpoint" -> endpointKind(jvm, kindParam);
            default -> throw V2Exception.unsupportedBy(
                    group + " is not a flame group", List.of("cpu", "alloc", "locks", "threads", "socket", "endpoint"));
        };
    }

    private String endpointKind(JvmRow jvm, String kindParam) {
        if (jvm.jdkMajor() < 25) {
            throw V2Exception.notOnJdk("needs JDK 25");
        }
        if (kindParam == null || kindParam.isBlank() || "wall".equals(kindParam)) {
            return "wall";
        }
        if ("cpu".equals(kindParam)) {
            return "cpu";
        }
        throw V2Exception.unsupportedBy("kind must be wall or cpu", List.of("wall", "cpu"));
    }

    private FrameKindSpec requireKind(JvmRow jvm, String kind) {
        if (kind == null || catalogue.frameKind(kind) == null) {
            throw V2Exception.unsupportedBy(
                    "unknown frame kind", catalogue.frameKinds().stream().map(FrameKindSpec::kind).toList());
        }
        FrameKindSpec spec = catalogue.frameKind(kind);
        if (!spec.onJdk(jvm.jdkMajor())) {
            throw V2Exception.notOnJdk("needs JDK " + spec.jdk());
        }
        return spec;
    }

    private static void rejectState(String kind, String state) {
        if (state != null && !state.isBlank() && !"wall".equals(kind)) {
            throw V2Exception.unsupportedBy("state is only valid for wall", List.of("wall"));
        }
    }

    private static List<String> flags(long n) {
        if (n < LOW_CONFIDENCE) {
            return List.of("low_confidence");
        }
        return List.of();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static V2Range range(JvmRow jvm, String last, String from, String to) {
        Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
        return V2Range.parse(last, from, to, end);
    }

    private static TimeWindow window(V2Range range) {
        return new TimeWindow(V2Times.text(range.from()), V2Times.text(range.to()));
    }

    private static FlameNode toNode(Node node, double floor) {
        List<FlameNode> children = new ArrayList<>();
        for (Node child : node.children.values()) {
            if (child.total < floor) {
                continue;
            }
            children.add(toNode(child, floor));
        }
        return new FlameNode(node.name, node.category, node.self, node.total, children);
    }

    private static final class Node {
        final String name;
        final String category;
        double self;
        double total;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String name, String category) {
            this.name = name;
            this.category = category;
        }

        Node child(String name, String category) {
            return children.computeIfAbsent(name, ignored -> new Node(name, category));
        }
    }

    private static final class Weighted {
        final List<Frame> frames;
        long count;
        double weight;
        double best;

        Weighted(List<Frame> frames) {
            this.frames = frames;
        }
    }

    private static final class Acc {
        final String category;
        double weight;
        long count;
        double best;
        String appCaller;

        Acc(String category) {
            this.category = category;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeWindow(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FlameNode(String name, String category, double self, double total, List<FlameNode> children) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Flame(String kind, TimeWindow window, long n, List<String> flags, FlameNode root) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrameRow(
            String frame,
            String category,
            double sharePct,
            String appCaller,
            long count,
            Double vsSharePct,
            Boolean significant) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PathRow(List<FlameNode> frames, double sharePct) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TopFrames(
            String kind,
            TimeWindow window,
            long n,
            List<String> flags,
            List<FrameRow> frames,
            List<PathRow> paths,
            Boolean significant) {}
}
