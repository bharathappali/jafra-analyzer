package io.jafra.analyzer.v2.rollup.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.jafra.analyzer.v2.rollup.ApSampling;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.rollup.Frames;
import io.jafra.analyzer.v2.rollup.LockCorrection;
import io.jafra.analyzer.v2.rollup.Seen;
import io.jafra.analyzer.v2.rollup.WallStates;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.rollup.extract.internal.InternalExtractors;

/**
 * Per-metric extractors registered by catalogue name. One chunk is already in {@link ChunkFacts}.
 * Counter rates are cumulative samples; {@code RollupWriter} turns them into Δ/Δt and skips a drop.
 */
public final class Extractors {
    /**
     * UNVERIFIED. true treats jdk.MethodTiming invocations as cumulative since instrumentation.
     * A feasibility note preferred per-chunk counts; flip this if a recording shows per-period values.
     */
    public static final boolean METHOD_TIMING_CUMULATIVE = true;

    public static final Set<String> NAMES = Set.of(
            "cpu.used.cores",
            "cpu.user.cores",
            "cpu.system.cores",
            "cpu.limit.cores",
            "cpu.container.pct_limit",
            "cpu.throttled.pct",
            "cpu.throttled.s_per_s",
            "cpu.jvm_user.pct",
            "cpu.jvm_system.pct",
            "cpu.machine.pct",
            "cpu.thread.pct",
            "cpu.samples.count",
            "heap.used.bytes",
            "heap.committed.bytes",
            "heap.max.bytes",
            "heap.used.pct_max",
            "heap.pool.used.bytes",
            "heap.pool.committed.bytes",
            "heap.space.used.bytes",
            "heap.after_gc.bytes",
            "heap.before_gc.bytes",
            "heap.occupancy.pct",
            "heap.promotion.bytes_per_s",
            "heap.survivor_age.bytes",
            "alloc.rate.bytes_per_s",
            "alloc.sampled.bytes",
            "alloc.gc_trigger.count",
            "gc.pause.us",
            "gc.pause.time_pct",
            "gc.count.per_s",
            "gc.full.count",
            "gc.cpu.pct",
            "gc.freed.bytes",
            "gc.copied.bytes",
            "gc.shenandoah.collected.bytes",
            "gc.phase.us",
            "gc.references.count",
            "gc.zgc.reloc_set.bytes",
            "gc.zgc.reloc_group.bytes",
            "gc.zgc.reloc_group.pages",
            "gc.zgc.page_alloc.bytes",
            "gc.zgc.uncommit.bytes",
            "gc.zgc.unmap.bytes",
            "gc.zgc.stall.us",
            "metaspace.used.bytes",
            "metaspace.committed.bytes",
            "metaspace.class_space.used.bytes",
            "metaspace.gc_threshold.bytes",
            "codecache.used.bytes",
            "codecache.reserved.bytes",
            "codecache.used.pct_reserved",
            "codecache.methods.count",
            "codecache.full.count",
            "nmt.committed.bytes",
            "nmt.reserved.bytes",
            "nmt.category.committed.bytes",
            "nmt.category.reserved.bytes",
            "nmt.untracked.bytes",
            "nmt.estimate.bytes",
            "nmt.direct.used.bytes",
            "nmt.direct.capacity.bytes",
            "nmt.direct.buffers.count",
            "container.usage.bytes",
            "container.limit.bytes",
            "container.usage.pct_limit",
            "container.rss.bytes",
            "container.swap.bytes",
            "container.mem_fail.count",
            "threads.live.count",
            "threads.daemon.count",
            "threads.peak.count",
            "threads.started.per_s",
            "threads.created.count",
            "threads.state.samples",
            "locks.wait.ms_per_s",
            "locks.wait.count",
            "locks.wait.ms",
            "vt.pinned.us",
            "vt.pinned.per_s",
            "vt.pinned.ms_per_s",
            "vt.submit_failed.count",
            "safepoint.duration.us",
            "safepoint.ttsp.us",
            "safepoint.time_pct",
            "safepoint.count.per_s",
            "jit.compile.us",
            "jit.compiles.per_s",
            "jit.compile_time.pct",
            "jit.deopts.per_min",
            "jit.compile_failed.count",
            "jit.bailouts.count",
            "jit.invalidated.count",
            "jit.queue.size",
            "jit.queue.added.per_s",
            "jit.queue.removed.per_s",
            "classes.loaded.per_s",
            "classes.unloaded.per_s",
            "classes.net.count",
            "classes.loader.classes.count",
            "classes.loader.hidden.count",
            "classes.loader.chunk.bytes",
            "socket.read.us",
            "socket.write.us",
            "socket.read.bytes",
            "socket.write.bytes",
            "socket.read_time.ms_per_s",
            "socket.net.rx.bytes_per_s",
            "socket.net.tx.bytes_per_s",
            "socket.tls.handshakes.per_s",
            "file.io.us",
            "file.io.bytes",
            "file.disk.iops",
            "file.disk.bytes_per_s",
            "exceptions.thrown.per_s",
            "exceptions.errors.count",
            "endpoint.calls.per_s",
            "endpoint.latency.us",
            "endpoint.slow.us",
            "endpoint.slow.count",
            "endpoint.slow.pct",
            "endpoint.slow.wait.pct");

    private Extractors() {}

    public static void extract(ChunkFacts facts, WindowData out) {
        cpu(facts, out);
        heap(facts, out);
        alloc(facts, out);
        gc(facts, out);
        metaspace(facts, out);
        code(facts, out);
        nmt(facts, out);
        container(facts, out);
        threads(facts, out);
        locks(facts, out);
        vt(facts, out);
        safepoint(facts, out);
        jit(facts, out);
        classes(facts, out);
        io(facts, out);
        exceptions(facts, out);
        endpoint(facts, out);
        events(facts, out);
        InternalExtractors.extract(facts, out);
    }

    /** Ratios that need counter rates already applied. */
    public static void derive(ChunkFacts facts, WindowData out) {
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        Double cores = out.value("cpu.used.cores");
        Double limit = out.value("cpu.limit.cores");
        if (cores != null && limit != null && limit > 0d) {
            out.add("cpu.container.pct_limit", "", "", 100d * cores / limit);
        }
        Double used = out.value("heap.used.bytes");
        Double max = out.value("heap.max.bytes");
        if (used != null && max != null && max > 0d) {
            out.add("heap.used.pct_max", "", "", 100d * used / max);
        }
        Double live = out.value("heap.after_gc.bytes");
        if (live != null && max != null && max > 0d) {
            out.add("heap.occupancy.pct", "", "", 100d * live / max);
        }
        if (dt > 0d && out.gcSumOfPausesUs > 0d) {
            out.add("gc.pause.time_pct", "", "", 100d * out.gcSumOfPausesUs / (dt * 1_000_000d));
        }
        if (dt > 0d && out.safepointSumUs > 0d) {
            out.add("safepoint.time_pct", "", "", 100d * out.safepointSumUs / (dt * 1_000_000d));
        }
        if (cores != null && cores > 0d && dt > 0d && out.gcCpuSeconds > 0d) {
            out.add("gc.cpu.pct", "", "", 100d * out.gcCpuSeconds / (cores * dt));
        }
        Double codeUsed = out.value("codecache.used.bytes");
        Double codeReserved = out.value("codecache.reserved.bytes");
        if (codeUsed != null && codeReserved != null && codeReserved > 0d) {
            out.add("codecache.used.pct_reserved", "", "", 100d * codeUsed / codeReserved);
        }
        Double memory = out.value("container.usage.bytes");
        Double memoryLimit = out.value("container.limit.bytes");
        if (memory != null && memoryLimit != null && memoryLimit > 0d) {
            out.add("container.usage.pct_limit", "", "", 100d * memory / memoryLimit);
        }
        if (out.rssBytes != null && out.nmtCommittedBytes != null) {
            out.add("nmt.untracked.bytes", "", "", out.rssBytes - out.nmtCommittedBytes);
        }
        Double outside = out.rssBytes != null ? out.rssBytes : out.containerUsageBytes;
        if (outside != null) {
            if (out.heapCommittedBytes != null) {
                outside -= out.heapCommittedBytes;
            }
            if (out.metaspaceCommittedBytes != null) {
                outside -= out.metaspaceCommittedBytes;
            }
            if (out.codeCacheUsedBytes != null) {
                outside -= out.codeCacheUsedBytes;
            }
            out.add("nmt.estimate.bytes", "", "", outside);
        }
        WindowData.Accum throttled = out.series().remove(new WindowData.SeriesKey("cpu.throttled.pct", "slices", ""));
        WindowData.Accum elapsed = out.series().remove(new WindowData.SeriesKey("cpu.throttled.pct", "elapsed", ""));
        if (throttled != null && elapsed != null && elapsed.avg() > 0d) {
            double pct = 100d * throttled.avg() / elapsed.avg();
            out.setSpan("cpu.throttled.pct", "", "", pct, pct, pct);
        }
        Double compileMsPerS = out.value("jit.compile_time.pct");
        if (compileMsPerS != null) {
            double pct = compileMsPerS / 10d;
            out.setSpan("jit.compile_time.pct", "", "", pct, pct, pct);
        }
        Double rate = out.value("alloc.rate.bytes_per_s");
        if (rate != null && out.allocWeightTotal > 0d && dt > 0d) {
            out.add("alloc.sampled.bytes", "", "", rate * dt);
            for (Map.Entry<String, Double> entry : out.allocWeights.entrySet()) {
                int eq = entry.getKey().indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                double bytes = entry.getValue() / out.allocWeightTotal * rate * dt;
                out.add("alloc.sampled.bytes", entry.getKey().substring(0, eq), entry.getKey(), bytes);
            }
        }
        if (dt > 0d) {
            Double calls = out.value("endpoint.calls.per_s");
            if (calls != null && calls > 0d) {
                out.add("endpoint.slow.pct", "", "", 100d * out.methodTraceCount / (calls * dt));
            }
            for (WindowData.SeriesKey key : List.copyOf(out.series().keySet())) {
                if (!"endpoint.calls.per_s".equals(key.metric()) || !key.key().startsWith("handler=")) {
                    continue;
                }
                double handlerRate = out.series().get(key).avg();
                if (handlerRate <= 0d) {
                    continue;
                }
                String handler = key.key().substring("handler=".length());
                int traces = out.methodTracesByHandler.getOrDefault(handler, 0);
                out.add("endpoint.slow.pct", "handler", key.key(), 100d * traces / (handlerRate * dt));
            }
        }
    }

    private static void cpu(ChunkFacts facts, WindowData out) {
        counterLast(facts, out, "jdk.ContainerCPUUsage", "cpuTime", "cpu.used.cores", 1e9);
        counterLast(facts, out, "jdk.ContainerCPUUsage", "cpuUserTime", "cpu.user.cores", 1e9);
        counterLast(facts, out, "jdk.ContainerCPUUsage", "cpuSystemTime", "cpu.system.cores", 1e9);
        Seen config = last(facts, "jdk.ContainerConfiguration");
        if (config != null) {
            Double quota = config.num("cpuQuota");
            Double period = first(config.num("cpuSlicePeriod"), config.num("cpuPeriod"));
            Double effective = config.num("effectiveCpuCount");
            double limit;
            if (quota != null && period != null && period > 0d && quota >= 0d) {
                limit = quota / period;
            } else if (effective != null) {
                limit = effective;
            } else {
                limit = Double.NaN;
            }
            if (!Double.isNaN(limit)) {
                out.add("cpu.limit.cores", "", "", limit);
            }
        }
        List<Seen> throttles = facts.ofType("jdk.ContainerCPUThrottling");
        if (!throttles.isEmpty()) {
            Seen throttle = throttles.get(throttles.size() - 1);
            Double slices = throttle.num("cpuThrottledSlices");
            Double elapsed = throttle.num("cpuElapsedSlices");
            if (slices != null) {
                out.counter("cpu.throttled.pct", "slices", "", slices);
            }
            if (elapsed != null) {
                out.counter("cpu.throttled.pct", "elapsed", "", elapsed);
            }
            Double time = throttle.num("cpuThrottledTime");
            if (time != null) {
                out.counter("cpu.throttled.s_per_s", "", "", time / 1e9);
            }
        }
        for (Seen load : facts.ofType("jdk.CPULoad")) {
            addPct(out, "cpu.jvm_user.pct", load.num("jvmUser"));
            addPct(out, "cpu.jvm_system.pct", load.num("jvmSystem"));
            addPct(out, "cpu.machine.pct", load.num("machineTotal"));
        }
        Map<String, Long> threadCounts = new LinkedHashMap<>();
        Map<String, Long> frameCounts = new LinkedHashMap<>();
        Map<String, Long> stateCounts = new LinkedHashMap<>();
        long samples = 0;
        for (Seen sample : facts.ofType("jdk.ExecutionSample")) {
            samples++;
            String thread = sample.thread;
            bump(threadCounts, thread);
            String frame = Frames.top(sample.frames);
            bump(frameCounts, frame);
            String state = sample.text("state");
            bump(stateCounts, state);
            long stackId = remember(out, sample.frames);
            out.sample("cpu", stackId, thread, state == null ? "" : state, handlerAt(facts, sample), 1, 1);
        }
        if (samples > 0) {
            out.add("cpu.samples.count", "", "", samples);
            flushCounts(out, "cpu.samples.count", "thread", threadCounts);
            flushCounts(out, "cpu.samples.count", "frame", frameCounts);
            flushCounts(out, "cpu.samples.count", "state", stateCounts);
        }
        for (Seen thread : facts.ofType("jdk.ThreadCPULoad")) {
            Double user = thread.num("user");
            Double system = thread.num("system");
            if (user == null && system == null) {
                continue;
            }
            double pct = 100d * ((user == null ? 0d : user) + (system == null ? 0d : system));
            String name = thread.thread.isBlank() ? thread.text("eventThread") : thread.thread;
            if (name != null && !name.isBlank()) {
                out.add("cpu.thread.pct", "thread", "thread=" + name, pct);
                out.add("cpu.thread.pct", "pool", "pool=" + Frames.pool(name), pct);
            }
        }
    }

    private static void heap(ChunkFacts facts, WindowData out) {
        List<Seen> usage = facts.ofType("jdk.GCHeapMemoryUsage");
        if (!usage.isEmpty()) {
            for (Seen event : usage) {
                addNum(out, "heap.used.bytes", event.num("used"));
                addNum(out, "heap.committed.bytes", event.num("committed"));
                addNum(out, "heap.max.bytes", event.num("max"));
            }
        }
        Seen config = last(facts, "jdk.GCHeapConfiguration");
        if (config != null && config.num("maxSize") != null && config.num("maxSize") > 0d) {
            out.add("heap.max.bytes", "", "", config.num("maxSize"));
        }
        Map<Long, Double> before = new LinkedHashMap<>();
        Map<Long, Double> after = new LinkedHashMap<>();
        Map<Long, String> collector = collectors(facts);
        Double minAfter = null;
        Double lastBefore = null;
        for (Seen summary : facts.ofType("jdk.GCHeapSummary")) {
            Double used = summary.num("heapUsed");
            if (used == null) {
                continue;
            }
            long gcId = summary.id("gcId");
            String when = summary.text("when");
            boolean isAfter = when != null && when.toLowerCase().contains("after");
            if (isAfter) {
                after.put(gcId, used);
                minAfter = minAfter == null ? used : Math.min(minAfter, used);
                String name = collector.get(gcId);
                if (name != null) {
                    out.add("heap.after_gc.bytes", "collector", "collector=" + name, used);
                }
            } else {
                before.put(gcId, used);
                lastBefore = used;
            }
            if (usage.isEmpty()) {
                out.add("heap.used.bytes", "", "", used);
            }
            Double committed = summary.num("heapSpace.committedSize");
            if (committed == null) {
                committed = summary.num("heapSpace.committed");
            }
            if (committed != null) {
                out.heapCommittedBytes = committed;
                if (usage.isEmpty()) {
                    out.add("heap.committed.bytes", "", "", committed);
                }
            }
        }
        if (minAfter != null) {
            out.setSpan("heap.after_gc.bytes", "", "", minAfter, minAfter, minAfter);
        }
        if (lastBefore != null) {
            out.add("heap.before_gc.bytes", "", "", lastBefore);
        }
        Map<String, Double> freedByCollector = new LinkedHashMap<>();
        double freed = 0;
        for (Map.Entry<Long, Double> entry : after.entrySet()) {
            Double was = before.get(entry.getKey());
            if (was == null) {
                continue;
            }
            double delta = was - entry.getValue();
            if (delta <= 0d) {
                continue;
            }
            freed += delta;
            String name = collector.get(entry.getKey());
            if (name != null) {
                freedByCollector.merge(name, delta, Double::sum);
            }
        }
        if (freed > 0d) {
            out.add("gc.freed.bytes", "", "", freed);
            flushDoubles(out, "gc.freed.bytes", "collector", freedByCollector);
        }
        for (Seen pool : facts.ofType("jdk.GCHeapMemoryPoolUsage")) {
            String name = firstText(pool, "name", "poolName", "type");
            addBy(out, "heap.pool.used.bytes", "pool", name, pool.num("used"));
            addBy(out, "heap.pool.committed.bytes", "pool", name, pool.num("committed"));
        }
        spaces(facts, out);
        for (Seen age : facts.ofType("jdk.TenuringDistribution")) {
            Double size = age.num("size");
            String years = age.text("age");
            if (years == null && age.num("age") != null) {
                years = Long.toString(age.num("age").longValue());
            }
            addBy(out, "heap.survivor_age.bytes", "age", years, size);
        }
        double promoted = 0;
        for (Seen g1 : facts.ofType("jdk.G1HeapSummary")) {
            Double oldBefore = null;
            Double oldAfter = null;
            String when = g1.text("when");
            Double old = g1.num("oldGenUsedSize");
            if (old == null) {
                continue;
            }
            if (when != null && when.toLowerCase().contains("after")) {
                oldAfter = old;
            } else if (when != null && when.toLowerCase().contains("before")) {
                oldBefore = old;
            }
            if (oldBefore != null && oldAfter != null && oldAfter > oldBefore) {
                promoted += oldAfter - oldBefore;
            }
        }
        if (promoted > 0d && out.dtSeconds > 0d) {
            out.add("heap.promotion.bytes_per_s", "", "", promoted / out.dtSeconds);
        }
    }

    private static void spaces(ChunkFacts facts, WindowData out) {
        for (Seen g1 : facts.ofType("jdk.G1HeapSummary")) {
            String when = g1.text("when");
            String whenKey = when == null ? "" : when.toLowerCase().contains("after") ? "after" : "before";
            addSpace(out, "eden", whenKey, g1.num("edenUsedSize"));
            addSpace(out, "survivor", whenKey, g1.num("survivorUsedSize"));
            Double old = g1.num("oldGenUsedSize");
            if (old == null) {
                Double heap = g1.num("heapUsed");
                Double eden = g1.num("edenUsedSize");
                Double survivor = g1.num("survivorUsedSize");
                if (heap != null && eden != null && survivor != null) {
                    old = heap - eden - survivor;
                }
            }
            addSpace(out, "old", whenKey, old);
        }
        for (Seen ps : facts.ofType("jdk.PSHeapSummary")) {
            addSpace(out, "eden", "", structUsed(ps, "edenSpace"));
            addSpace(out, "old", "", structUsed(ps, "oldObjectSpace"));
        }
    }

    private static void alloc(ChunkFacts facts, WindowData out) {
        Map<String, Double> threadAlloc = new LinkedHashMap<>();
        Map<String, Double> poolAlloc = new LinkedHashMap<>();
        double total = 0;
        boolean any = false;
        for (Seen stat : facts.ofType("jdk.ThreadAllocationStatistics")) {
            Double allocated = stat.num("allocated");
            if (allocated == null) {
                continue;
            }
            any = true;
            String thread = stat.thread.isBlank() ? stat.text("threadName") : stat.thread;
            if (thread == null) {
                thread = "";
            }
            threadAlloc.put(thread, allocated);
            total += allocated;
            if (!thread.isBlank()) {
                poolAlloc.merge(Frames.pool(thread), allocated, Double::sum);
            }
        }
        if (any) {
            out.counter("alloc.rate.bytes_per_s", "", "", total);
            for (Map.Entry<String, Double> entry : threadAlloc.entrySet()) {
                if (!entry.getKey().isBlank()) {
                    out.counter("alloc.rate.bytes_per_s", "thread", "thread=" + entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, Double> entry : poolAlloc.entrySet()) {
                out.counter("alloc.rate.bytes_per_s", "pool", "pool=" + entry.getKey(), entry.getValue());
            }
        }
        for (String type : List.of("jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB")) {
            for (Seen sample : facts.ofType(type)) {
                Double weight = sample.num(ApSampling.ALLOC_WEIGHT_FIELD);
                if (weight == null) {
                    weight = sample.num("tlabSize");
                }
                if (weight == null || weight <= 0d) {
                    continue;
                }
                out.allocWeightTotal += weight;
                String className = firstText(sample, "objectClass", "class");
                out.allocWeight("class", className, weight);
                out.allocWeight("site", Frames.firstNonJdk(sample.frames), weight);
                out.allocWeight("thread", sample.thread, weight);
                long stackId = remember(out, sample.frames);
                out.sample("alloc", stackId, sample.thread, "", "", 1, weight);
            }
        }
        Map<String, Long> sites = new LinkedHashMap<>();
        for (Seen trigger : facts.ofType("jdk.AllocationRequiringGC")) {
            bump(sites, Frames.firstNonJdk(trigger.frames));
        }
        if (!sites.isEmpty()) {
            long totalSites = sites.values().stream().mapToLong(Long::longValue).sum();
            out.add("alloc.gc_trigger.count", "", "", totalSites);
            flushCounts(out, "alloc.gc_trigger.count", "site", sites);
        }
    }

    private static void gc(ChunkFacts facts, WindowData out) {
        Map<Long, Seen> byId = index(facts, "jdk.GarbageCollection", "gcId");
        boolean phased = false;
        for (Seen pause : facts.ofType("jdk.GCPhasePause")) {
            Double us = pause.num("duration");
            if (us == null || us < 0d) {
                continue;
            }
            phased = true;
            out.addHist("gc.pause.us", "", us);
            Seen gc = byId.get(pause.id("gcId"));
            if (gc != null) {
                histBy(out, "gc.pause.us", "collector", gc.text("name"), us);
                histBy(out, "gc.pause.us", "cause", gc.text("cause"), us);
            }
            String phase = pause.text("name");
            out.add("gc.phase.us", "", "", us);
            addBy(out, "gc.phase.us", "phase", phase, us);
            out.add("gc.phase.us", "kind", "kind=pause", us);
        }
        for (Seen concurrent : facts.ofType("jdk.GCPhaseConcurrent")) {
            Double us = concurrent.num("duration");
            if (us == null) {
                continue;
            }
            out.add("gc.phase.us", "", "", us);
            addBy(out, "gc.phase.us", "phase", concurrent.text("name"), us);
            out.add("gc.phase.us", "kind", "kind=concurrent", us);
        }
        double sum = 0;
        Map<String, Long> byCollector = new LinkedHashMap<>();
        Map<String, Long> byCause = new LinkedHashMap<>();
        Set<Long> fullIds = new java.util.HashSet<>();
        for (Seen old : facts.ofType("jdk.OldGarbageCollection")) {
            fullIds.add(old.id("gcId"));
        }
        for (Seen g1 : facts.ofType("jdk.G1GarbageCollection")) {
            String type = g1.text("type");
            if (type != null && type.toLowerCase().contains("full")) {
                fullIds.add(g1.id("gcId"));
            }
        }
        if (!byId.isEmpty()) {
            for (Seen gc : byId.values()) {
                Double pauses = gc.num("sumOfPauses");
                if (pauses != null) {
                    sum += pauses;
                }
                if (!phased) {
                    Double longest = gc.num("longestPause");
                    if (longest != null && longest > 0d) {
                        out.addHist("gc.pause.us", "", longest);
                        histBy(out, "gc.pause.us", "collector", gc.text("name"), longest);
                        histBy(out, "gc.pause.us", "cause", gc.text("cause"), longest);
                    }
                }
                bump(byCollector, gc.text("name"));
                bump(byCause, gc.text("cause"));
            }
            out.gcSumOfPausesUs = sum;
            double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
            if (dt > 0d) {
                out.add("gc.count.per_s", "", "", byId.size() / dt);
                flushRates(out, "gc.count.per_s", "collector", byCollector, dt);
                flushRates(out, "gc.count.per_s", "cause", byCause, dt);
            }
        }
        if (!fullIds.isEmpty()) {
            out.add("gc.full.count", "", "", fullIds.size());
        }
        double cpuUs = 0;
        for (Seen time : facts.ofType("jdk.GCCPUTime")) {
            cpuUs += seconds(time.num("userTime"));
            cpuUs += seconds(time.num("systemTime"));
        }
        out.gcCpuSeconds = cpuUs;
        double copied = 0;
        for (Seen evac : facts.ofType("jdk.EvacuationInformation")) {
            Double bytes = evac.num("bytesCopied");
            if (bytes != null) {
                copied += bytes;
            }
        }
        if (copied > 0d) {
            out.add("gc.copied.bytes", "", "", copied);
        }
        Map<String, Double> shen = new LinkedHashMap<>();
        for (Seen event : facts.ofType("jdk.ShenandoahEvacuationInformation")) {
            addPart(shen, "young", event.num("collectedYoung"));
            addPart(shen, "old", event.num("collectedOld"));
        }
        if (!shen.isEmpty()) {
            double total = shen.values().stream().mapToDouble(Double::doubleValue).sum();
            out.add("gc.shenandoah.collected.bytes", "", "", total);
            flushDoubles(out, "gc.shenandoah.collected.bytes", "gen", shen);
        }
        for (Seen ref : facts.ofType("jdk.GCReferenceStatistics")) {
            addBy(out, "gc.references.count", "type", firstText(ref, "type", "referenceType"), ref.num("count"));
        }
        for (Seen reloc : facts.ofType("jdk.ZRelocationSet")) {
            addBy(out, "gc.zgc.reloc_set.bytes", "part", "total", reloc.num("total"));
            addBy(out, "gc.zgc.reloc_set.bytes", "part", "empty", reloc.num("empty"));
            addBy(out, "gc.zgc.reloc_set.bytes", "part", "relocate", reloc.num("relocate"));
        }
        for (Seen group : facts.ofType("jdk.ZRelocationSetGroup")) {
            String page = firstText(group, "pageType", "type");
            addBy(out, "gc.zgc.reloc_group.bytes", "page_type", page, group.num("relocate"));
            addBy(out, "gc.zgc.reloc_group.pages", "page_type", page, group.num("selectedPages"));
        }
        for (Seen page : facts.ofType("jdk.ZPageAllocation")) {
            addBy(out, "gc.zgc.page_alloc.bytes", "page_type", firstText(page, "pageType", "type"), page.num("size"));
        }
        sumField(facts, out, "jdk.ZUncommit", "uncommitted", "gc.zgc.uncommit.bytes");
        sumField(facts, out, "jdk.ZUnmap", "unmapped", "gc.zgc.unmap.bytes");
        for (Seen stall : facts.ofType("jdk.ZAllocationStall")) {
            Double us = stall.num("duration");
            if (us == null) {
                continue;
            }
            out.add("gc.zgc.stall.us", "", "", us);
            addBy(out, "gc.zgc.stall.us", "type", firstText(stall, "type", "allocationType"), us);
        }
    }

    private static void metaspace(ChunkFacts facts, WindowData out) {
        Seen chosen = null;
        for (Seen summary : facts.ofType("jdk.MetaspaceSummary")) {
            String when = summary.text("when");
            if (when != null && when.toLowerCase().contains("after")) {
                chosen = summary;
            } else if (chosen == null) {
                chosen = summary;
            }
        }
        if (chosen != null) {
            Double used = first(chosen.num("metaspace.used"), chosen.num("used"));
            Double committed = first(chosen.num("metaspace.committed"), chosen.num("committed"));
            Double classUsed = first(chosen.num("classSpace.used"), chosen.num("classSpaceUsed"));
            Double threshold = first(chosen.num("gcThreshold"), chosen.num("metaspace.gcThreshold"));
            addNum(out, "metaspace.used.bytes", used);
            addNum(out, "metaspace.committed.bytes", committed);
            addNum(out, "metaspace.class_space.used.bytes", classUsed);
            addNum(out, "metaspace.gc_threshold.bytes", threshold);
            if (committed != null) {
                out.metaspaceCommittedBytes = committed;
            }
        }
    }

    private static void code(ChunkFacts facts, WindowData out) {
        double used = 0;
        double reserved = 0;
        double methods = 0;
        boolean any = false;
        for (Seen stat : facts.ofType("jdk.CodeCacheStatistics")) {
            Double start = stat.num("startAddress");
            Double top = stat.num("reservedTopAddress");
            Double free = stat.num("unallocatedCapacity");
            String heap = firstText(stat, "codeBlobType", "codeHeap");
            if (start != null && top != null && free != null && top >= start) {
                any = true;
                double span = top - start;
                double heapUsed = span - free;
                used += heapUsed;
                reserved += span;
                addBy(out, "codecache.used.bytes", "code_heap", heap, heapUsed);
                addBy(out, "codecache.reserved.bytes", "code_heap", heap, span);
            }
            Double methodCount = stat.num("methodCount");
            if (methodCount != null) {
                methods += methodCount;
                addBy(out, "codecache.methods.count", "code_heap", heap, methodCount);
            }
            Double full = stat.num("fullCount");
            if (full != null) {
                out.counter("codecache.full.count", "", "", full);
                if (heap != null) {
                    out.counter("codecache.full.count", "code_heap", "code_heap=" + heap, full);
                }
            }
        }
        if (any) {
            out.add("codecache.used.bytes", "", "", used);
            out.add("codecache.reserved.bytes", "", "", reserved);
            out.codeCacheUsedBytes = used;
        }
        if (methods > 0d) {
            out.add("codecache.methods.count", "", "", methods);
        }
        Seen config = last(facts, "jdk.CodeCacheConfiguration");
        if (config != null && config.num("reservedSize") != null) {
            out.add("codecache.reserved.bytes", "", "", config.num("reservedSize"));
        }
    }

    private static void nmt(ChunkFacts facts, WindowData out) {
        Seen total = last(facts, "jdk.NativeMemoryUsageTotal");
        if (total != null) {
            addNum(out, "nmt.committed.bytes", total.num("committed"));
            addNum(out, "nmt.reserved.bytes", total.num("reserved"));
            if (total.num("committed") != null) {
                out.nmtCommittedBytes = total.num("committed");
            }
        }
        for (Seen category : facts.ofType("jdk.NativeMemoryUsage")) {
            String name = firstText(category, "type", "name");
            addBy(out, "nmt.category.committed.bytes", "category", name, category.num("committed"));
            addBy(out, "nmt.category.reserved.bytes", "category", name, category.num("reserved"));
        }
        Seen direct = last(facts, "jdk.DirectBufferStatistics");
        if (direct != null) {
            addNum(out, "nmt.direct.used.bytes", first(direct.num("memoryUsed"), direct.num("used")));
            addNum(out, "nmt.direct.capacity.bytes", first(direct.num("totalCapacity"), direct.num("capacity")));
            addNum(out, "nmt.direct.buffers.count", direct.num("count"));
        }
    }

    private static void container(ChunkFacts facts, WindowData out) {
        for (Seen memory : facts.ofType("jdk.ContainerMemoryUsage")) {
            Double usage = memory.num("memoryUsage");
            addNum(out, "container.usage.bytes", usage);
            if (usage != null) {
                out.containerUsageBytes = usage;
            }
            addNum(out, "container.swap.bytes", memory.num("swapMemoryUsage"));
            Double fails = memory.num("memoryFailCount");
            if (fails != null) {
                out.counter("container.mem_fail.count", "", "", fails);
            }
        }
        Seen config = last(facts, "jdk.ContainerConfiguration");
        if (config != null) {
            addNum(out, "container.limit.bytes", config.num("memoryLimit"));
        }
        Seen rss = last(facts, "jdk.ResidentSetSize");
        if (rss != null && rss.num("size") != null) {
            out.rssBytes = rss.num("size");
            out.add("container.rss.bytes", "", "", rss.num("size"));
        }
    }

    private static void threads(ChunkFacts facts, WindowData out) {
        for (Seen stat : facts.ofType("jdk.JavaThreadStatistics")) {
            addNum(out, "threads.live.count", stat.num("activeCount"));
            addNum(out, "threads.daemon.count", stat.num("daemonCount"));
            addNum(out, "threads.peak.count", stat.num("peakCount"));
            if (stat.num("accumulatedCount") != null) {
                out.counter("threads.started.per_s", "", "", stat.num("accumulatedCount"));
            }
        }
        Map<String, Long> creators = new LinkedHashMap<>();
        Map<String, Long> sites = new LinkedHashMap<>();
        for (Seen start : facts.ofType("jdk.ThreadStart")) {
            bump(creators, firstText(start, "parentThread"));
            bump(sites, Frames.firstApp(start.frames));
        }
        if (!creators.isEmpty() || !sites.isEmpty()) {
            long total = facts.ofType("jdk.ThreadStart").size();
            out.add("threads.created.count", "", "", total);
            flushCounts(out, "threads.created.count", "creator", creators);
            flushCounts(out, "threads.created.count", "site", sites);
        }
        Map<String, Long> states = new LinkedHashMap<>();
        Map<String, Long> pools = new LinkedHashMap<>();
        Map<String, Long> names = new LinkedHashMap<>();
        long wall = 0;
        for (Seen sample : facts.ofType("profiler.WallClockSample")) {
            double count = sample.num("samples") == null ? 1d : sample.num("samples");
            wall += (long) count;
            String state = WallStates.classify(sample.text("state"));
            states.merge(state, (long) count, Long::sum);
            if (!sample.thread.isBlank()) {
                pools.merge(Frames.pool(sample.thread), (long) count, Long::sum);
                names.merge(sample.thread, (long) count, Long::sum);
            }
            long stackId = remember(out, sample.frames);
            out.sample("wall", stackId, sample.thread, state, handlerAt(facts, sample), (long) count, count);
        }
        if (wall > 0) {
            out.add("threads.state.samples", "", "", wall);
            flushCounts(out, "threads.state.samples", "state", states);
            flushCounts(out, "threads.state.samples", "pool", pools);
            flushCounts(out, "threads.state.samples", "thread", names);
        }
    }

    private static void locks(ChunkFacts facts, WindowData out) {
        double correctedMs = 0;
        double correctedN = 0;
        Map<String, Double> waitByMonitor = new LinkedHashMap<>();
        Map<String, Double> waitByThread = new LinkedHashMap<>();
        for (String type : List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark")) {
            for (Seen event : facts.ofType(type)) {
                Double us = event.num("duration");
                double ms = us == null ? 0d : us / 1000d;
                double corrected = LockCorrection.correctedWaitMs(ms, facts.lockIntervalMs);
                correctedMs += corrected;
                correctedN += LockCorrection.correctedCount(ms, facts.lockIntervalMs);
                String monitor = firstText(event, "monitorClass", "klass", "address", "parkedClass");
                if (monitor != null) {
                    waitByMonitor.merge(monitor, ms, Double::sum);
                    out.add("locks.wait.ms", "monitor", "monitor=" + monitor, ms);
                }
                if (!event.thread.isBlank()) {
                    waitByThread.merge(event.thread, ms, Double::sum);
                    out.add("locks.wait.ms", "thread", "thread=" + event.thread, ms);
                }
                if (ms > 0d) {
                    out.add("locks.wait.ms", "", "", ms);
                }
                long stackId = remember(out, event.frames);
                out.sample("lock", stackId, event.thread, "", "", 1, ms);
            }
        }
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        if (correctedN > 0d && dt > 0d) {
            out.add("locks.wait.ms_per_s", "", "", correctedMs / dt);
            out.add("locks.wait.count", "", "", correctedN);
            for (Map.Entry<String, Double> entry : waitByMonitor.entrySet()) {
                double share = correctedMs == 0d ? 0d : entry.getValue() / (correctedMs) ;
                out.add("locks.wait.ms_per_s", "monitor", "monitor=" + entry.getKey(), (correctedMs / dt) * share);
            }
            for (Map.Entry<String, Double> entry : waitByThread.entrySet()) {
                double share = correctedMs == 0d ? 0d : entry.getValue() / correctedMs;
                out.add("locks.wait.ms_per_s", "thread", "thread=" + entry.getKey(), (correctedMs / dt) * share);
            }
        }
    }

    private static void vt(ChunkFacts facts, WindowData out) {
        double sumUs = 0;
        int count = 0;
        Map<String, Long> sites = new LinkedHashMap<>();
        for (Seen pin : facts.ofType("jdk.VirtualThreadPinned")) {
            Double us = pin.num("duration");
            if (us == null) {
                continue;
            }
            count++;
            sumUs += us;
            out.addHist("vt.pinned.us", "", us);
            String site = Frames.firstApp(pin.frames);
            histBy(out, "vt.pinned.us", "site", site, us);
            histBy(out, "vt.pinned.us", "carrier", pin.text("carrierThread"), us);
            histBy(out, "vt.pinned.us", "reason", pin.text("pinnedReason"), us);
            bump(sites, site);
        }
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        if (count > 0 && dt > 0d) {
            out.add("vt.pinned.per_s", "", "", count / dt);
            out.add("vt.pinned.ms_per_s", "", "", (sumUs / 1000d) / dt);
            flushRates(out, "vt.pinned.per_s", "site", sites, dt);
        }
        int failed = facts.ofType("jdk.VirtualThreadSubmitFailed").size();
        if (failed > 0) {
            out.add("vt.submit_failed.count", "", "", failed);
        }
    }

    private static void safepoint(ChunkFacts facts, WindowData out) {
        Map<Long, Double> begin = new LinkedHashMap<>();
        Map<Long, Double> operation = new LinkedHashMap<>();
        Map<Long, String> name = new LinkedHashMap<>();
        for (Seen event : facts.ofType("jdk.SafepointBegin")) {
            if (event.num("duration") != null) {
                begin.put(event.id("safepointId"), event.num("duration"));
            }
        }
        for (Seen event : facts.ofType("jdk.ExecuteVMOperation")) {
            long id = event.id("safepointId");
            if (event.num("duration") != null) {
                operation.put(id, event.num("duration"));
            }
            if (event.text("operation") != null) {
                name.put(id, event.text("operation"));
            }
        }
        double sum = 0;
        Map<String, Long> ops = new LinkedHashMap<>();
        for (Long id : union(begin.keySet(), operation.keySet())) {
            double duration = begin.getOrDefault(id, 0d) + operation.getOrDefault(id, 0d);
            sum += duration;
            out.addHist("safepoint.duration.us", "", duration);
            String op = name.get(id);
            histBy(out, "safepoint.duration.us", "operation", op, duration);
            bump(ops, op);
        }
        out.safepointSumUs = sum;
        for (Seen ttsp : facts.ofType("jdk.SafepointStateSynchronization")) {
            Double us = ttsp.num("duration");
            if (us != null) {
                out.addHist("safepoint.ttsp.us", "", us);
            }
        }
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        if (!begin.isEmpty() && dt > 0d) {
            out.add("safepoint.count.per_s", "", "", begin.size() / dt);
            flushRates(out, "safepoint.count.per_s", "operation", ops, dt);
        }
    }

    private static void jit(ChunkFacts facts, WindowData out) {
        for (Seen compile : facts.ofType("jdk.Compilation")) {
            Double us = compile.num("duration");
            if (us == null) {
                continue;
            }
            out.addHist("jit.compile.us", "", us);
            histBy(out, "jit.compile.us", "compiler", compile.text("compiler"), us);
            String level = compile.num("compileLevel") == null
                    ? null
                    : Long.toString(compile.num("compileLevel").longValue());
            histBy(out, "jit.compile.us", "level", level, us);
            histBy(out, "jit.compile.us", "method", compile.text("method"), us);
        }
        Seen stats = last(facts, "jdk.CompilerStatistics");
        if (stats != null) {
            if (stats.num("compileCount") != null) {
                out.counter("jit.compiles.per_s", "", "", stats.num("compileCount"));
            }
            if (stats.num("totalTimeSpent") != null) {
                out.counter("jit.compile_time.pct", "", "", stats.num("totalTimeSpent"));
            }
            if (stats.num("bailoutCount") != null) {
                out.counter("jit.bailouts.count", "", "", stats.num("bailoutCount"));
            }
            if (stats.num("invalidatedCount") != null) {
                out.counter("jit.invalidated.count", "", "", stats.num("invalidatedCount"));
            }
        }
        Map<String, Long> methods = new LinkedHashMap<>();
        Map<String, Long> reasons = new LinkedHashMap<>();
        Map<String, Long> actions = new LinkedHashMap<>();
        for (Seen deopt : facts.ofType("jdk.Deoptimization")) {
            bump(methods, deopt.text("method"));
            bump(reasons, deopt.text("reason"));
            bump(actions, deopt.text("action"));
        }
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        if (!methods.isEmpty() && dt > 0d) {
            double perMin = 60d * facts.ofType("jdk.Deoptimization").size() / dt;
            out.add("jit.deopts.per_min", "", "", perMin);
            flushRates(out, "jit.deopts.per_min", "method", methods, dt / 60d);
            flushRates(out, "jit.deopts.per_min", "reason", reasons, dt / 60d);
            flushRates(out, "jit.deopts.per_min", "action", actions, dt / 60d);
        }
        int failed = facts.ofType("jdk.CompilationFailure").size();
        if (failed > 0) {
            out.add("jit.compile_failed.count", "", "", failed);
        }
        for (Seen queue : facts.ofType("jdk.CompilerQueueUtilization")) {
            String compiler = firstText(queue, "compiler", "type");
            addBy(out, "jit.queue.size", "compiler", compiler, queue.num("queueSize"));
            addBy(out, "jit.queue.added.per_s", "compiler", compiler, queue.num("addedRate"));
            addBy(out, "jit.queue.removed.per_s", "compiler", compiler, queue.num("removedRate"));
            addNum(out, "jit.queue.size", queue.num("queueSize"));
            addNum(out, "jit.queue.added.per_s", queue.num("addedRate"));
            addNum(out, "jit.queue.removed.per_s", queue.num("removedRate"));
        }
    }

    private static void classes(ChunkFacts facts, WindowData out) {
        Seen stat = last(facts, "jdk.ClassLoadingStatistics");
        if (stat != null) {
            if (stat.num("loadedClassCount") != null) {
                out.counter("classes.loaded.per_s", "", "", stat.num("loadedClassCount"));
            }
            if (stat.num("unloadedClassCount") != null) {
                out.counter("classes.unloaded.per_s", "", "", stat.num("unloadedClassCount"));
            }
            Double loaded = stat.num("loadedClassCount");
            Double unloaded = stat.num("unloadedClassCount");
            if (loaded != null) {
                out.add("classes.net.count", "", "", loaded - (unloaded == null ? 0d : unloaded));
            }
        }
        for (Seen loader : facts.ofType("jdk.ClassLoaderStatistics")) {
            String name = firstText(loader, "classLoader", "loader");
            addBy(out, "classes.loader.classes.count", "loader", name, loader.num("classCount"));
            addBy(out, "classes.loader.hidden.count", "loader", name, loader.num("hiddenClassCount"));
            addBy(out, "classes.loader.chunk.bytes", "loader", name, loader.num("chunkSize"));
        }
    }

    private static void io(ChunkFacts facts, WindowData out) {
        double readUs = 0;
        Map<String, Double> readBytes = new LinkedHashMap<>();
        Map<String, Double> writeBytes = new LinkedHashMap<>();
        for (Seen read : facts.ofType("jdk.SocketRead")) {
            Double us = read.num("duration");
            String peer = peer(read);
            if (us != null) {
                readUs += us;
                out.addHist("socket.read.us", "", us);
                histBy(out, "socket.read.us", "peer", peer, us);
                histBy(out, "socket.read.us", "thread", blank(read.thread), us);
                long stackId = remember(out, read.frames);
                out.sample("socket", stackId, read.thread, "", "", 1, us);
            }
            Double bytes = read.num("bytesRead");
            if (bytes != null) {
                readBytes.merge(peer == null ? "" : peer, bytes, Double::sum);
            }
        }
        for (Seen write : facts.ofType("jdk.SocketWrite")) {
            Double us = write.num("duration");
            String peer = peer(write);
            if (us != null) {
                out.addHist("socket.write.us", "", us);
                histBy(out, "socket.write.us", "peer", peer, us);
                histBy(out, "socket.write.us", "thread", blank(write.thread), us);
                long stackId = remember(out, write.frames);
                out.sample("socket", stackId, write.thread, "", "", 1, us);
            }
            Double bytes = write.num("bytesWritten");
            if (bytes != null) {
                writeBytes.merge(peer == null ? "" : peer, bytes, Double::sum);
            }
        }
        double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
        if (readUs > 0d && dt > 0d) {
            out.add("socket.read_time.ms_per_s", "", "", (readUs / 1000d) / dt);
        }
        if (!readBytes.isEmpty()) {
            double total = readBytes.values().stream().mapToDouble(Double::doubleValue).sum();
            out.add("socket.read.bytes", "", "", total);
            flushDoubles(out, "socket.read.bytes", "peer", readBytes);
        }
        if (!writeBytes.isEmpty()) {
            double total = writeBytes.values().stream().mapToDouble(Double::doubleValue).sum();
            out.add("socket.write.bytes", "", "", total);
            flushDoubles(out, "socket.write.bytes", "peer", writeBytes);
        }
        for (Seen net : facts.ofType("jdk.NetworkUtilization")) {
            String iface = firstText(net, "networkInterface", "interfaceName", "name");
            Double readRate = net.num("readRate");
            Double writeRate = net.num("writeRate");
            if (readRate != null) {
                addBy(out, "socket.net.rx.bytes_per_s", "interface", iface, readRate / 8d);
                out.add("socket.net.rx.bytes_per_s", "", "", readRate / 8d);
            }
            if (writeRate != null) {
                addBy(out, "socket.net.tx.bytes_per_s", "interface", iface, writeRate / 8d);
                out.add("socket.net.tx.bytes_per_s", "", "", writeRate / 8d);
            }
        }
        int handshakes = facts.ofType("jdk.TLSHandshake").size();
        if (handshakes > 0 && dt > 0d) {
            out.add("socket.tls.handshakes.per_s", "", "", handshakes / dt);
            Map<String, Long> peers = new LinkedHashMap<>();
            Map<String, Long> protocols = new LinkedHashMap<>();
            for (Seen handshake : facts.ofType("jdk.TLSHandshake")) {
                bump(peers, peer(handshake));
                bump(protocols, handshake.text("protocolVersion"));
            }
            flushRates(out, "socket.tls.handshakes.per_s", "peer", peers, dt);
            flushRates(out, "socket.tls.handshakes.per_s", "protocol", protocols, dt);
        }
        fileOps(facts, out, "jdk.FileRead", "read", "bytesRead");
        fileOps(facts, out, "jdk.FileWrite", "write", "bytesWritten");
        fileOps(facts, out, "jdk.FileForce", "force", null);
        Seen disk = last(facts, "jdk.ContainerIOUsage");
        if (disk != null) {
            if (disk.num("serviceRequests") != null) {
                out.counter("file.disk.iops", "", "", disk.num("serviceRequests"));
            }
            if (disk.num("dataTransferred") != null) {
                out.counter("file.disk.bytes_per_s", "", "", disk.num("dataTransferred"));
            }
        }
    }

    private static void fileOps(ChunkFacts facts, WindowData out, String type, String op, String bytesField) {
        Map<String, Double> bytes = new LinkedHashMap<>();
        for (Seen event : facts.ofType(type)) {
            Double us = event.num("duration");
            String path = event.text("path");
            if (us != null) {
                out.addHist("file.io.us", "", us);
                histBy(out, "file.io.us", "op", op, us);
                histBy(out, "file.io.us", "path", path, us);
                histBy(out, "file.io.us", "thread", blank(event.thread), us);
            }
            if (bytesField != null && event.num(bytesField) != null) {
                bytes.merge(op + "|" + (path == null ? "" : path), event.num(bytesField), Double::sum);
            }
        }
        for (Map.Entry<String, Double> entry : bytes.entrySet()) {
            int bar = entry.getKey().indexOf('|');
            String operation = entry.getKey().substring(0, bar);
            String path = entry.getKey().substring(bar + 1);
            out.add("file.io.bytes", "op", "op=" + operation, entry.getValue());
            if (!path.isBlank()) {
                out.add("file.io.bytes", "path", "path=" + path, entry.getValue());
            }
            out.add("file.io.bytes", "", "", entry.getValue());
        }
    }

    private static void exceptions(ChunkFacts facts, WindowData out) {
        Seen stat = last(facts, "jdk.ExceptionStatistics");
        if (stat != null && stat.num("throwables") != null) {
            out.counter("exceptions.thrown.per_s", "", "", stat.num("throwables"));
        }
        Map<String, Long> classes = new LinkedHashMap<>();
        Map<String, Long> sites = new LinkedHashMap<>();
        for (Seen error : facts.ofType("jdk.JavaErrorThrow")) {
            bump(classes, error.text("thrownClass"));
            bump(sites, Frames.firstApp(error.frames));
        }
        if (!classes.isEmpty()) {
            out.add("exceptions.errors.count", "", "", facts.ofType("jdk.JavaErrorThrow").size());
            flushCounts(out, "exceptions.errors.count", "class", classes);
            flushCounts(out, "exceptions.errors.count", "site", sites);
        }
    }

    private static void endpoint(ChunkFacts facts, WindowData out) {
        Map<String, Double> invocations = new LinkedHashMap<>();
        for (Seen timing : facts.ofType("jdk.MethodTiming")) {
            String handler = timing.text("method");
            Double calls = timing.num("invocations");
            if (handler == null || calls == null) {
                continue;
            }
            invocations.put(handler, calls);
            Double avg = timing.num("average");
            Double min = timing.num("minimum");
            Double max = timing.num("maximum");
            if (avg != null) {
                double lo = min == null ? avg : min;
                double hi = max == null ? avg : max;
                out.setSpan("endpoint.latency.us", "handler", "handler=" + handler, lo, hi, avg);
            }
        }
        if (!invocations.isEmpty()) {
            double total = invocations.values().stream().mapToDouble(Double::doubleValue).sum();
            if (METHOD_TIMING_CUMULATIVE) {
                out.counter("endpoint.calls.per_s", "", "", total);
                for (Map.Entry<String, Double> entry : invocations.entrySet()) {
                    out.counter("endpoint.calls.per_s", "handler", "handler=" + entry.getKey(), entry.getValue());
                }
            } else {
                double dt = out.dtSeconds > 0d ? out.dtSeconds : facts.dtSeconds;
                if (dt > 0d) {
                    out.add("endpoint.calls.per_s", "", "", total / dt);
                    for (Map.Entry<String, Double> entry : invocations.entrySet()) {
                        out.add("endpoint.calls.per_s", "handler", "handler=" + entry.getKey(), entry.getValue() / dt);
                    }
                }
            }
        }
        Map<String, Long> handlers = new LinkedHashMap<>();
        for (Seen trace : facts.ofType("jdk.MethodTrace")) {
            Double us = trace.num("duration");
            String handler = trace.text("method");
            if (us != null) {
                out.addHist("endpoint.slow.us", "", us);
                histBy(out, "endpoint.slow.us", "handler", handler, us);
                histBy(out, "endpoint.slow.us", "thread", blank(trace.thread), us);
            }
            bump(handlers, handler);
            out.methodTraceCount++;
            if (handler != null) {
                out.methodTracesByHandler.merge(handler, 1, Integer::sum);
            }
        }
        if (!handlers.isEmpty()) {
            long total = handlers.values().stream().mapToLong(Long::longValue).sum();
            out.add("endpoint.slow.count", "", "", total);
            flushCounts(out, "endpoint.slow.count", "handler", handlers);
        }
        waitShare(facts, out);
    }

    private static void waitShare(ChunkFacts facts, WindowData out) {
        List<Seen> calls = facts.ofType("jdk.MethodTrace");
        if (calls.isEmpty()) {
            return;
        }
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Seen wall : facts.ofType("profiler.WallClockSample")) {
            Seen call = enclosing(calls, wall);
            if (call == null) {
                continue;
            }
            String handler = call.text("method");
            if (handler == null) {
                continue;
            }
            String cause = waitCause(facts, wall);
            long weight = wall.num("samples") == null ? 1L : wall.num("samples").longValue();
            counts.computeIfAbsent(handler, ignored -> new LinkedHashMap<>()).merge(cause, weight, Long::sum);
        }
        for (Map.Entry<String, Map<String, Long>> handler : counts.entrySet()) {
            long total = handler.getValue().values().stream().mapToLong(Long::longValue).sum();
            if (total <= 0) {
                continue;
            }
            for (Map.Entry<String, Long> cause : handler.getValue().entrySet()) {
                double pct = 100d * cause.getValue() / total;
                out.add("endpoint.slow.wait.pct", "handler", "handler=" + handler.getKey(), pct);
                out.add("endpoint.slow.wait.pct", "wait_cause", "wait_cause=" + cause.getKey(), pct);
            }
        }
    }

    private static void events(ChunkFacts facts, WindowData out) {
        Map<Long, Seen> gcById = index(facts, "jdk.GarbageCollection", "gcId");
        Map<Long, Double> before = heapWhen(facts, false);
        Map<Long, Double> after = heapWhen(facts, true);
        for (Seen gc : facts.ofType("jdk.GarbageCollection")) {
            long id = gc.id("gcId");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("collector", gc.text("name"));
            fields.put("cause", gc.text("cause"));
            fields.put("gc_id", id);
            fields.put("duration_us", gc.num("longestPause"));
            fields.put("sum_of_pauses_us", gc.num("sumOfPauses"));
            fields.put("heap_before", before.get(id));
            fields.put("heap_after", after.get(id));
            emit(out, "gc.pause", gc.startMs, gc.num("longestPause"), gc.thread, Frames.top(gc.frames), gc.frames, fields);
        }
        Map<Long, Seen> begins = index(facts, "jdk.SafepointBegin", "safepointId");
        Map<Long, Seen> ops = index(facts, "jdk.ExecuteVMOperation", "safepointId");
        Map<Long, Seen> ttsps = index(facts, "jdk.SafepointStateSynchronization", "safepointId");
        for (Long id : union(begins.keySet(), ops.keySet())) {
            Seen begin = begins.get(id);
            Seen op = ops.get(id);
            Seen ttsp = ttsps.get(id);
            double duration = (begin == null || begin.num("duration") == null ? 0d : begin.num("duration"))
                    + (op == null || op.num("duration") == null ? 0d : op.num("duration"));
            Seen carrier = begin != null ? begin : op;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("operation", op == null ? null : op.text("operation"));
            fields.put("duration_us", duration);
            fields.put("ttsp_us", ttsp == null ? null : ttsp.num("duration"));
            fields.put("threads", begin == null ? null : begin.num("totalThreadCount"));
            emit(out, "safepoint", carrier.startMs, duration, carrier.thread, Frames.top(carrier.frames), carrier.frames, fields);
        }
        for (Seen trace : facts.ofType("jdk.MethodTrace")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("handler", trace.text("method"));
            fields.put("duration_us", trace.num("duration"));
            fields.put("thread", trace.thread);
            int[] split = wallSplit(facts, trace);
            int total = split[0] + split[1];
            fields.put("running_pct", total == 0 ? null : 100d * split[0] / total);
            fields.put("waiting_pct", total == 0 ? null : 100d * split[1] / total);
            emit(out, "endpoint.slow", trace.startMs, trace.num("duration"), trace.thread, Frames.top(trace.frames), trace.frames, fields);
        }
        for (Seen error : facts.ofType("jdk.JavaErrorThrow")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("class", error.text("thrownClass"));
            fields.put("message", error.text("message"));
            fields.put("top_frame", Frames.top(error.frames));
            emit(out, "error", error.startMs, null, error.thread, Frames.top(error.frames), error.frames, fields);
            if ("java.lang.OutOfMemoryError".equals(error.text("thrownClass"))) {
                Map<String, Object> oom = new LinkedHashMap<>();
                oom.put("kind", "heap");
                oom.put("message", error.text("message"));
                oom.put("top_frame", Frames.top(error.frames));
                emit(out, "oom", error.startMs, null, error.thread, Frames.top(error.frames), error.frames, oom);
            }
        }
        for (Seen pin : facts.ofType("jdk.VirtualThreadPinned")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("duration_us", pin.num("duration"));
            fields.put("thread", pin.thread);
            fields.put("top_frame", Frames.top(pin.frames));
            fields.put("carrier", pin.text("carrierThread"));
            fields.put("reason", pin.text("pinnedReason"));
            fields.put("blocking_op", pin.text("blockingOperation"));
            emit(out, "vt.pinned", pin.startMs, pin.num("duration"), pin.thread, Frames.top(pin.frames), pin.frames, fields);
        }
        for (Seen failed : facts.ofType("jdk.VirtualThreadSubmitFailed")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("thread_id", failed.num("javaThreadId"));
            fields.put("message", failed.text("exceptionMessage"));
            fields.put("top_frame", Frames.top(failed.frames));
            emit(out, "vt.submit_failed", failed.startMs, null, failed.thread, Frames.top(failed.frames), failed.frames, fields);
        }
        for (Seen handshake : facts.ofType("jdk.TLSHandshake")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("peer_host", handshake.text("peerHost"));
            fields.put("peer_port", handshake.num("peerPort"));
            fields.put("protocol", handshake.text("protocolVersion"));
            fields.put("cipher", handshake.text("cipherSuite"));
            fields.put("top_frame", Frames.top(handshake.frames));
            emit(out, "tls.handshake", handshake.startMs, null, handshake.thread, Frames.top(handshake.frames), handshake.frames, fields);
        }
        socketEvent(facts, out, "jdk.SocketRead", "read", "bytesRead");
        socketEvent(facts, out, "jdk.SocketWrite", "write", "bytesWritten");
        fileEvent(facts, out, "jdk.FileRead", "read", "bytesRead");
        fileEvent(facts, out, "jdk.FileWrite", "write", "bytesWritten");
        fileEvent(facts, out, "jdk.FileForce", "force", null);
        for (Seen compile : facts.ofType("jdk.Compilation")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("method", compile.text("method"));
            fields.put("compiler", compile.text("compiler"));
            fields.put("level", compile.num("compileLevel"));
            fields.put("duration_us", compile.num("duration"));
            fields.put("succeeded", compile.text("succeded") != null ? compile.text("succeded") : compile.text("succeeded"));
            fields.put("osr", compile.text("isOsr"));
            fields.put("code_size", compile.num("codeSize"));
            emit(out, "jit.compile", compile.startMs, compile.num("duration"), compile.thread, compile.text("method"), compile.frames, fields);
        }
        for (Seen deopt : facts.ofType("jdk.Deoptimization")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("method", deopt.text("method"));
            fields.put("reason", deopt.text("reason"));
            fields.put("action", deopt.text("action"));
            fields.put("line", deopt.num("lineNumber"));
            fields.put("bci", deopt.num("bci"));
            emit(out, "jit.deopt", deopt.startMs, null, deopt.thread, deopt.text("method"), deopt.frames, fields);
        }
        for (Seen failure : facts.ofType("jdk.CompilationFailure")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("compile_id", failure.num("compileId"));
            fields.put("message", failure.text("failureMessage"));
            emit(out, "jit.compile_failed", failure.startMs, null, failure.thread, null, failure.frames, fields);
        }
        for (Seen full : facts.ofType("jdk.CodeCacheFull")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("code_heap", full.text("codeBlobType"));
            fields.put("full_count", full.num("fullCount"));
            fields.put("unallocated", full.num("unallocatedCapacity"));
            emit(out, "codecache.full", full.startMs, null, full.thread, null, full.frames, fields);
        }
        for (Seen threshold : facts.ofType("jdk.MetaspaceGCThreshold")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("old", threshold.num("oldValue"));
            fields.put("new", threshold.num("newValue"));
            fields.put("updater", threshold.text("updater"));
            emit(out, "metaspace.threshold", threshold.startMs, null, threshold.thread, null, threshold.frames, fields);
        }
        for (Seen failure : facts.ofType("jdk.MetaspaceAllocationFailure")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("loader", failure.text("classLoader"));
            fields.put("size", failure.num("size"));
            fields.put("type", failure.text("metadataType"));
            fields.put("top_frame", Frames.top(failure.frames));
            emit(out, "metaspace.alloc_failure", failure.startMs, null, failure.thread, Frames.top(failure.frames), failure.frames, fields);
        }
        for (Seen oom : facts.ofType("jdk.MetaspaceOOM")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kind", "metaspace");
            fields.put("loader", oom.text("classLoader"));
            fields.put("size", oom.num("size"));
            fields.put("top_frame", Frames.top(oom.frames));
            emit(out, "oom", oom.startMs, null, oom.thread, Frames.top(oom.frames), oom.frames, fields);
        }
        for (Seen evac : facts.ofType("jdk.EvacuationFailed")) {
            long gcId = evac.id("gcId");
            Seen gc = gcById.get(gcId);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("gc_id", gcId);
            fields.put("object_count", evac.num("evacuationFailed.objectCount"));
            fields.put("first_size_bytes", evac.num("evacuationFailed.firstSize"));
            fields.put("smallest_size_bytes", evac.num("evacuationFailed.smallestSize"));
            fields.put("total_size_bytes", evac.num("evacuationFailed.totalSize"));
            if (gc != null && gc.num("longestPause") != null) {
                fields.put("pause_us", gc.num("longestPause"));
            }
            emit(out, "gc.evac_failed", evac.startMs, gc == null ? null : gc.num("longestPause"), evac.thread, Frames.top(evac.frames), evac.frames, fields);
        }
        for (Seen promo : facts.ofType("jdk.PromotionFailed")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("gc_id", promo.id("gcId"));
            fields.put("object_count", promo.num("promotionFailed.objectCount"));
            fields.put("first_size_bytes", promo.num("promotionFailed.firstSize"));
            fields.put("smallest_size_bytes", promo.num("promotionFailed.smallestSize"));
            fields.put("total_size_bytes", promo.num("promotionFailed.totalSize"));
            fields.put("thread", promo.thread);
            emit(out, "gc.promotion_failed", promo.startMs, null, promo.thread, Frames.top(promo.frames), promo.frames, fields);
        }
        for (Seen system : facts.ofType("jdk.SystemGC")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("duration_us", system.num("duration"));
            fields.put("thread", system.thread);
            fields.put("top_frame", Frames.firstApp(system.frames));
            fields.put("invoked_concurrent", system.text("invokedConcurrent"));
            emit(out, "gc.system_gc", system.startMs, system.num("duration"), system.thread, Frames.firstApp(system.frames), system.frames, fields);
        }
    }

    private static void socketEvent(ChunkFacts facts, WindowData out, String type, String op, String bytes) {
        for (Seen event : facts.ofType(type)) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("op", op);
            fields.put("duration_us", event.num("duration"));
            fields.put("host", event.text("host"));
            fields.put("port", event.num("port"));
            fields.put("bytes", event.num(bytes));
            fields.put("top_frame", Frames.top(event.frames));
            emit(out, "socket.slow", event.startMs, event.num("duration"), event.thread, Frames.top(event.frames), event.frames, fields);
        }
    }

    private static void fileEvent(ChunkFacts facts, WindowData out, String type, String op, String bytes) {
        for (Seen event : facts.ofType(type)) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("op", op);
            fields.put("duration_us", event.num("duration"));
            fields.put("path", event.text("path"));
            if (bytes != null) {
                fields.put("bytes", event.num(bytes));
            }
            fields.put("top_frame", Frames.top(event.frames));
            emit(out, "file.slow", event.startMs, event.num("duration"), event.thread, Frames.top(event.frames), event.frames, fields);
        }
    }

    private static String handlerAt(ChunkFacts facts, Seen sample) {
        Seen call = enclosing(facts.ofType("jdk.MethodTrace"), sample);
        return call == null ? "" : blank(call.text("method"));
    }

    private static Seen enclosing(List<Seen> calls, Seen sample) {
        Seen found = null;
        for (Seen call : calls) {
            if (!call.thread.equals(sample.thread)) {
                continue;
            }
            if (sample.startMs >= call.startMs && sample.startMs <= call.endMs) {
                found = call;
            }
        }
        return found;
    }

    private static String waitCause(ChunkFacts facts, Seen wall) {
        if ("running".equals(WallStates.classify(wall.text("state")))) {
            return "running";
        }
        if (overlaps(facts, "jdk.SocketRead", wall) || overlaps(facts, "jdk.SocketWrite", wall)) {
            return "socket";
        }
        if (overlaps(facts, "jdk.JavaMonitorEnter", wall) || overlaps(facts, "jdk.ThreadPark", wall)) {
            return "lock";
        }
        if (overlaps(facts, "jdk.GCPhasePause", wall)) {
            return "GC";
        }
        return "waiting";
    }

    private static boolean overlaps(ChunkFacts facts, String type, Seen sample) {
        for (Seen event : facts.ofType(type)) {
            if (!event.thread.isBlank() && !event.thread.equals(sample.thread)) {
                continue;
            }
            if (sample.startMs >= event.startMs && sample.startMs <= event.endMs) {
                return true;
            }
        }
        return false;
    }

    private static int[] wallSplit(ChunkFacts facts, Seen call) {
        int running = 0;
        int waiting = 0;
        for (Seen wall : facts.ofType("profiler.WallClockSample")) {
            if (!wall.thread.equals(call.thread)) {
                continue;
            }
            if (wall.startMs < call.startMs || wall.startMs > call.endMs) {
                continue;
            }
            int weight = wall.num("samples") == null ? 1 : wall.num("samples").intValue();
            if ("running".equals(WallStates.classify(wall.text("state")))) {
                running += weight;
            } else {
                waiting += weight;
            }
        }
        return new int[] {running, waiting};
    }

    private static void emit(
            WindowData out,
            String type,
            long ts,
            Double durationUs,
            String thread,
            String top,
            List<Frame> frames,
            Map<String, Object> fields) {
        Long stackId = null;
        if (frames != null && !frames.isEmpty()) {
            stackId = remember(out, frames);
        }
        Long duration = durationUs == null ? null : durationUs.longValue();
        out.event(new WindowData.StoredEvent(type, ts, duration, blank(thread), top, stackId, json(fields)));
    }

    private static long remember(WindowData out, List<Frame> frames) {
        long id = Frames.hash(frames);
        out.stack(id, framesJson(frames));
        return id;
    }

    private static String framesJson(List<Frame> frames) {
        StringBuilder builder = new StringBuilder("[");
        if (frames != null) {
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                Frame frame = frames.get(i);
                builder.append("{\"frame\":").append(quote(frame.name()));
                builder.append(",\"category\":").append(quote(frame.category()));
                builder.append(",\"type\":").append(quote(frame.type()));
                builder.append(",\"line\":").append(frame.line()).append('}');
            }
        }
        return builder.append(']').toString();
    }

    private static String json(Map<String, Object> fields) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            } else {
                builder.append(quote(String.valueOf(value)));
            }
        }
        return builder.append('}').toString();
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void counterLast(
            ChunkFacts facts, WindowData out, String type, String field, String metric, double divisor) {
        Seen event = last(facts, type);
        if (event == null || event.num(field) == null) {
            return;
        }
        out.counter(metric, "", "", event.num(field) / divisor);
    }

    private static void addPct(WindowData out, String metric, Double fraction) {
        if (fraction != null) {
            out.add(metric, "", "", 100d * fraction);
        }
    }

    private static void addNum(WindowData out, String metric, Double value) {
        if (value != null) {
            out.add(metric, "", "", value);
        }
    }

    private static void addBy(WindowData out, String metric, String by, String value, Double number) {
        if (number == null || value == null || value.isBlank()) {
            return;
        }
        out.add(metric, by, by + "=" + value, number);
    }

    private static void histBy(WindowData out, String metric, String by, String value, double microseconds) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.addHist(metric, by + "=" + value, microseconds);
    }

    private static void addSpace(WindowData out, String space, String when, Double bytes) {
        if (bytes == null) {
            return;
        }
        out.add("heap.space.used.bytes", "space", "space=" + space, bytes);
        if (when != null && !when.isBlank()) {
            out.add("heap.space.used.bytes", "when", "when=" + when, bytes);
        }
    }

    private static void flushCounts(WindowData out, String metric, String by, Map<String, Long> counts) {
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.add(metric, by, by + "=" + entry.getKey(), entry.getValue());
        }
    }

    private static void flushDoubles(WindowData out, String metric, String by, Map<String, Double> values) {
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.add(metric, by, by + "=" + entry.getKey(), entry.getValue());
        }
    }

    private static void flushRates(WindowData out, String metric, String by, Map<String, Long> counts, double dt) {
        if (dt <= 0d) {
            return;
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            out.add(metric, by, by + "=" + entry.getKey(), entry.getValue() / dt);
        }
    }

    private static void sumField(ChunkFacts facts, WindowData out, String type, String field, String metric) {
        double total = 0;
        boolean any = false;
        for (Seen event : facts.ofType(type)) {
            if (event.num(field) != null) {
                total += event.num(field);
                any = true;
            }
        }
        if (any) {
            out.add(metric, "", "", total);
        }
    }

    private static void addPart(Map<String, Double> target, String key, Double value) {
        if (value != null) {
            target.merge(key, value, Double::sum);
        }
    }

    private static double seconds(Double microsecondsOrNanos) {
        if (microsecondsOrNanos == null) {
            return 0d;
        }
        if (microsecondsOrNanos > 1e12d) {
            return microsecondsOrNanos / 1e9d;
        }
        return microsecondsOrNanos / 1e6d;
    }

    private static Map<Long, Seen> index(ChunkFacts facts, String type, String field) {
        Map<Long, Seen> indexed = new LinkedHashMap<>();
        for (Seen event : facts.ofType(type)) {
            long id = event.id(field);
            if (id >= 0) {
                indexed.put(id, event);
            }
        }
        return indexed;
    }

    private static Map<Long, String> collectors(ChunkFacts facts) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Seen gc : facts.ofType("jdk.GarbageCollection")) {
            if (gc.text("name") != null) {
                names.put(gc.id("gcId"), gc.text("name"));
            }
        }
        return names;
    }

    private static Map<Long, Double> heapWhen(ChunkFacts facts, boolean after) {
        Map<Long, Double> values = new LinkedHashMap<>();
        for (Seen summary : facts.ofType("jdk.GCHeapSummary")) {
            String when = summary.text("when");
            boolean isAfter = when != null && when.toLowerCase().contains("after");
            if (isAfter == after && summary.num("heapUsed") != null) {
                values.put(summary.id("gcId"), summary.num("heapUsed"));
            }
        }
        return values;
    }

    private static Seen last(ChunkFacts facts, String type) {
        List<Seen> events = facts.ofType(type);
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    private static String firstText(Seen event, String... fields) {
        for (String field : fields) {
            String text = event.text(field);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static Double first(Double... values) {
        for (Double value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double structUsed(Seen event, String prefix) {
        return first(event.num(prefix + ".used"), event.num(prefix + ".size"));
    }

    private static String peer(Seen event) {
        String host = event.text("host");
        if (host == null) {
            host = event.text("peerHost");
        }
        Double port = event.num("port");
        if (port == null) {
            port = event.num("peerPort");
        }
        if (host == null) {
            return null;
        }
        if (port == null) {
            return host;
        }
        return host + ":" + port.longValue();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static void bump(Map<String, Long> counts, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        counts.merge(key, 1L, Long::sum);
    }

    private static List<Long> union(java.util.Collection<Long> left, java.util.Collection<Long> right) {
        List<Long> ids = new ArrayList<>(left);
        for (Long id : right) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
