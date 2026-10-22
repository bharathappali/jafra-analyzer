package io.jafra.analyzer.v2.incidents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Store-backed views for one committed window. */
final class WindowViews {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EVENT_CAP = 10_000;

    private WindowViews() {}

    static RollupView rollup(V2Store store, int jvmId, long fromMs) {
        return new RollupView() {
            private Map<String, RollupRow> totals;

            @Override
            public Optional<RollupRow> total(String metric) {
                return Optional.ofNullable(totals().get(metric));
            }

            @Override
            public List<RollupRow> breakdown(String metric, String by) {
                try {
                    return store.readBreakdown(jvmId, metric, by, fromMs);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public Map<Integer, Long> hist(String metric, String key) {
                try {
                    return store.readHist(jvmId, metric, key == null ? "" : key, fromMs, fromMs + 1);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public Optional<RollupRow> lastBefore(String metric) {
                try {
                    return Optional.ofNullable(store.lastTotalBefore(jvmId, metric, fromMs));
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public List<RollupRow> recentBreakdown(String metric, String by, int windows) {
                try {
                    List<Long> starts = store.recentWindowStarts(jvmId, fromMs, Math.max(1, windows));
                    Map<String, double[]> sums = new LinkedHashMap<>();
                    for (long ts : starts) {
                        for (RollupRow row : store.readBreakdown(jvmId, metric, by, ts)) {
                            double[] acc = sums.computeIfAbsent(row.key(), ignored -> new double[] {
                                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0d, 0d
                            });
                            acc[0] = Math.min(acc[0], row.min());
                            acc[1] = Math.max(acc[1], row.max());
                            acc[2] += row.count();
                            acc[3] += row.sum();
                        }
                    }
                    List<RollupRow> rows = new ArrayList<>();
                    for (Map.Entry<String, double[]> entry : sums.entrySet()) {
                        double[] acc = entry.getValue();
                        long count = (long) acc[2];
                        double sum = acc[3];
                        double avg = count == 0 ? 0d : sum / count;
                        rows.add(new RollupRow(jvmId, metric, by, entry.getKey(), fromMs, acc[0], acc[1], avg, count, sum));
                    }
                    rows.sort(Comparator.comparingDouble(RollupRow::sum).reversed());
                    return rows;
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            private Map<String, RollupRow> totals() {
                if (totals == null) {
                    try {
                        totals = store.readTotalsAt(jvmId, fromMs);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }
                return totals;
            }
        };
    }

    static SampleView samples(V2Store store, int jvmId, long fromMs, long toMs) {
        return new SampleView() {
            @Override
            public List<SampleRow> rows(String kind) {
                return read(kind, fromMs, toMs);
            }

            @Override
            public List<SampleRow> recent(String kind, int windows) {
                try {
                    List<Long> starts = store.recentWindowStarts(jvmId, fromMs, Math.max(1, windows));
                    if (starts.isEmpty()) {
                        return rows(kind);
                    }
                    long oldest = starts.get(starts.size() - 1);
                    return read(kind, oldest, toMs);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public List<Frame> stack(long stackId) {
                try {
                    String json = store.readStackJson(List.of(stackId)).get(stackId);
                    if (json == null || json.isBlank()) {
                        return List.of();
                    }
                    List<Frame> frames = new ArrayList<>();
                    for (JsonNode node : JSON.readTree(json)) {
                        frames.add(new Frame(
                                node.path("frame").asText(""),
                                node.path("category").asText(""),
                                node.path("type").asText(""),
                                node.path("line").asInt(0)));
                    }
                    return frames;
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            private List<SampleRow> read(String kind, long from, long to) {
                try {
                    return store.readSamples(jvmId, kind, from, to, null, null, null);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        };
    }

    static EventView events(V2Store store, int jvmId, long fromMs, long toMs) {
        return new EventView() {
            @Override
            public List<EventRow> of(String type) {
                try {
                    return store.readEvents(jvmId, List.of(type), fromMs, toMs, false, EVENT_CAP);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public List<EventRow> internal(String name) {
                try {
                    return store.readInternalEvents(jvmId, name, fromMs, toMs);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        };
    }
}
