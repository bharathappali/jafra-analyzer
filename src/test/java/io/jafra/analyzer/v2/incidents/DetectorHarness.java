package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Action;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Mark;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Open;

/** Runs detector verdicts through the real lifecycle and the together post-pass. */
public final class DetectorHarness {
    private DetectorHarness() {}

    public record Result(String id, String level, Long t0, Open open, Open closed, List<Action> actions) {}

    public static Result run(Detector detector, List<WindowInput> windows) {
        return run(detector, windows, List.of());
    }

    public static Result run(Detector detector, List<WindowInput> windows, List<TogetherRule> rules) {
        IncidentLifecycle life = new IncidentLifecycle();
        List<Action> actions = new ArrayList<>();
        String id = null;
        Long t0 = null;
        for (WindowInput window : windows) {
            WindowVerdict verdict = detector.evaluate(window);
            if (rules != null && !rules.isEmpty()) {
                Map<String, WindowVerdict> verdicts = new LinkedHashMap<>();
                verdicts.put(detector.type(), verdict);
                TogetherContext context = new TogetherContext(verdicts, List.of());
                for (TogetherRule rule : rules) {
                    rule.apply(context);
                }
                verdict = context.verdict(detector.type());
            }
            double value = verdict.value() == null ? 0d : verdict.value();
            double score = IncidentLifecycle.score(value, verdict.band());
            Action action = life.accept(detector.kind(), new Mark(window.fromMs(), window.toMs(), verdict, score));
            actions.add(action);
            if (action == Action.OPEN && life.open() != null) {
                t0 = life.open().t0();
                long seconds = t0 / 1000L;
                id = detector.type() + "-" + seconds;
            }
        }
        String level = null;
        Open open = life.open();
        if (open != null && open.worst() != null && open.worst().verdict() != null) {
            level = open.worst().verdict().state().name().toLowerCase();
        }
        return new Result(id, level, t0, open, life.lastClosed(), actions);
    }
}
