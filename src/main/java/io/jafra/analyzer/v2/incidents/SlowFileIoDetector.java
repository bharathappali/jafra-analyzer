package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Slow file reads and writes. Log paths get an async-logging todo. */
@ApplicationScoped
public class SlowFileIoDetector implements Detector {
    private final double warning;
    private final double critical;
    private final Pattern logs;

    @Inject
    public SlowFileIoDetector(DetectorBands bands) {
        this(bands.slowFileIo().warningThreadS(), bands.slowFileIo().criticalThreadS(), bands.slowFileIo().logPath());
    }

    public SlowFileIoDetector(double warning, double critical, String logPath) {
        this.warning = warning;
        this.critical = critical;
        this.logs = Pattern.compile(logPath);
    }

    @Override
    public String type() { return "slow_file_io"; }
    @Override
    public String group() { return "file"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.FILE_IO).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.FILE_IO + " row", Set.of("lower_bound"));
        }
        double micros = in.window().total(DetectorMetrics.FILE_IO).map(RollupRow::sum).orElse(0d);
        double seconds = Math.max(0.001d, (in.toMs() - in.fromMs()) / 1000d);
        double value = (micros / 1_000_000d) / seconds;
        State state = value >= critical ? State.CRITICAL : value >= warning ? State.WARNING : State.OK;
        List<RollupRow> paths = in.window().breakdown(DetectorMetrics.FILE_IO, "path");
        String path = paths.isEmpty() ? "unknown" : paths.get(0).key();
        String todo = logs.matcher(path).find()
                ? "use async logging or log less (" + path + ")"
                : "check the disk/volume behind " + path;
        return Verdicts.of(state, value, "thread_s_per_s", null, new Band(warning, critical), family(),
                DetectorSupport.band(warning + " / " + critical + " thread-s/s"),
                new Who("site", path, null, false), List.of(), List.of(todo),
                "Threads spent " + HeapSizeAdvice.number(value) + " s per second in slow file reads/writes.",
                "Slow file IO " + HeapSizeAdvice.number(value) + " thread-s/s",
                List.of("jdk.FileRead", "jdk.FileWrite"), Set.of("lower_bound"), false);
    }
}
