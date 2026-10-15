package io.jafra.analyzer.findings;

import java.util.ArrayList;
import java.util.List;

/** Numbers a detector is allowed to see for one instance window. Missing series stay empty. */
public final class WindowSnapshot {
    public long windowNs;
    public boolean observed;
    public String collector;
    public double pauseMs;
    public long youngGcs;
    public final List<Double> heapAfter = new ArrayList<>();
    public Long heapMax;
    public long allocationStalls;
    public final List<Double> allocPerSec = new ArrayList<>();
    public double allocBytes;
    public int lowTenuringGcs;
    public int survivorFullGcs;
    public double promotedBytes;
    public long humongousRegions;
    public long regionCount;
    public boolean humongousCause;
    public long evacuationFailures;
    public final List<Double> metaspace = new ArrayList<>();
    public double classesLoaded;
    public double classesUnloaded;
    public boolean metadataGcCause;
    public double throttledSlices;
    public double elapsedSlices;
    public double jvmCores;
    public Double cpuLimitCores;
    public Double effectiveCpus;
    public double topMethodShare;
    public long topSamples;
    public double lockMs;
    public double monitorShare;
    public double parkedGroupShare;
    public final List<Double> socketP99 = new ArrayList<>();
    public final List<Double> fileP99 = new ArrayList<>();
    public long safepointCount;
    public double safepointMs;
    public long deoptCount;
    public boolean codeCacheFull;
    public final List<Double> rss = new ArrayList<>();
    public final List<Double> heapCommitted = new ArrayList<>();
    public boolean nmtRising;
    public final List<Double> liveThreads = new ArrayList<>();
    public double threadStarts;
    public double threadEnds;
    public double throwables;
    public double memoryUsage;
    public Double memLimit;
    public boolean ergonomicSerial;
}
