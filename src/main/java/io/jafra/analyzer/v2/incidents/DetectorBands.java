package io.jafra.analyzer.v2.incidents;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Proposal bands, pending KUSMA. Keys match jafra.v2.detect.* in application.properties. */
@ConfigMapping(prefix = "jafra.v2.detect")
public interface DetectorBands {
    LongPause longPause();

    HeapNearFull heapNearFull();

    ContainerNearLimit containerNearLimit();

    GcOverhead gcOverhead();

    FullGc fullGc();

    ZgcAllocStall zgcAllocStall();

    interface LongPause {
        @WithDefault("200")
        double warningMs();

        @WithDefault("1000")
        double criticalMs();
    }

    interface HeapNearFull {
        @WithDefault("85")
        double warningPct();

        @WithDefault("95")
        double criticalPct();
    }

    interface ContainerNearLimit {
        @WithDefault("90")
        double warningPct();

        @WithDefault("97")
        double criticalPct();
    }

    interface GcOverhead {
        @WithDefault("10")
        double warningPct();

        @WithDefault("25")
        double criticalPct();
    }

    interface FullGc {
        @WithDefault("3")
        int criticalCount();

        @WithDefault("12")
        int windowCount();
    }

    interface ZgcAllocStall {
        @WithDefault("1")
        double criticalPct();
    }

    MethodTiming methodTiming();

    interface MethodTiming {
        @WithDefault("AUTO")
        String mode();
    }

    OldGenRising oldGenRising();

    AllocSpike allocSpike();

    OvercommittedConfig overcommittedConfig();

    MetaspaceGrowth metaspaceGrowth();

    LoaderLeak loaderLeak();

    DirectBufferLeak directBufferLeak();

    NativeGrowth nativeGrowth();

    CpuThrottling cpuThrottling();

    CpuSaturation cpuSaturation();

    CpuJump cpuJump();

    HotFrameShift hotFrameShift();

    LockContention lockContention();

    SingleHotLock singleHotLock();

    ThreadChurn threadChurn();

    ThreadLeak threadLeak();

    PoolSaturation poolSaturation();

    EventLoopBlocked eventLoopBlocked();

    PinningBurst pinningBurst();

    TtspSpike ttspSpike();

    SafepointOverhead safepointOverhead();

    DeoptStorm deoptStorm();

    JitBacklog jitBacklog();

    ClassLoadBurst classLoadBurst();

    ClassUnloadBurst classUnloadBurst();

    SlowPeer slowPeer();

    TlsChurn tlsChurn();

    SlowFileIo slowFileIo();

    ExceptionStorm exceptionStorm();

    EndpointSlowdown endpointSlowdown();

    SlowRequests slowRequests();

    interface OldGenRising {
        @WithDefault("60")
        double warningMin();

        @WithDefault("10")
        double criticalMin();

        @WithDefault("90")
        double occupancyPct();
    }

    interface AllocSpike {
        @WithDefault("4")
        double z();

        @WithDefault("2")
        double multiple();
    }

    interface OvercommittedConfig {
        @WithDefault("1.25")
        double heapFactor();

        @WithDefault("0.8")
        double heapShare();
    }

    interface MetaspaceGrowth {
        @WithDefault("60")
        double warningHeadroomMin();
    }

    interface LoaderLeak {
        @WithDefault("30")
        int minPoints();
    }

    interface DirectBufferLeak {
        @WithDefault("80")
        double warningPct();

        @WithDefault("95")
        double criticalPct();
    }

    interface NativeGrowth {
        @WithDefault("10")
        double warningSlopePct();

        @WithDefault("15")
        double criticalHeadroomMin();
    }

    interface CpuThrottling {
        @WithDefault("25")
        double warningPct();

        @WithDefault("50")
        double criticalPct();

        @WithDefault("0.2")
        double criticalSeconds();
    }

    interface CpuSaturation {
        @WithDefault("90")
        double warningPct();

        @WithDefault("98")
        double criticalPct();

        @WithDefault("25")
        double throttlePct();
    }

    interface CpuJump {
        @WithDefault("4")
        double z();

        @WithDefault("1.5")
        double multiple();
    }

    interface HotFrameShift {
        @WithDefault("0.3")
        double distance();

        @WithDefault("1.3")
        double cpuMultiple();

        @WithDefault("200")
        int minSamples();
    }

    interface LockContention {
        @WithDefault("0.5")
        double warningThreadS();

        @WithDefault("2")
        double criticalThreadS();

        @WithDefault("4")
        double z();

        @WithDefault("raw")
        String waitFormula();

        @WithDefault("10")
        double intervalLowMs();

        @WithDefault("1")
        double intervalMediumMs();

        @WithDefault("0.1")
        double intervalAggressiveMs();

        @WithDefault("java.util.concurrent.ThreadPoolExecutor.getTask,java.util.concurrent.ForkJoinPool.awaitWork,java.util.concurrent.SynchronousQueue")
        String idleFrames();
    }

    interface SingleHotLock {
        @WithDefault("60")
        double sharePct();

        @WithDefault("0.5")
        double minThreadS();

        @WithDefault("50")
        int minSamples();
    }

    interface ThreadChurn {
        @WithDefault("20")
        double warningPerS();

        @WithDefault("100")
        double criticalPerS();

        @WithDefault("4")
        double z();
    }

    interface ThreadLeak {
        @WithDefault("2")
        double multiple();

        @WithDefault("60")
        int windows();
    }

    interface PoolSaturation {
        @WithDefault("95")
        double warningPct();

        @WithDefault("100")
        int minSamples();

        @WithDefault("http-nio-,executor-thread-,exec-,qtp,XNIO-")
        String prefixes();

        @WithDefault("org.apache.tomcat.util.threads.TaskQueue.take,org.apache.tomcat.util.threads.TaskQueue.poll")
        String idleFrames();
    }

    interface EventLoopBlocked {
        @WithDefault("20")
        double warningPct();

        @WithDefault("50")
        double criticalPct();

        @WithDefault("100")
        int minSamples();

        @WithDefault("vert.x-eventloop-thread-,reactor-http-nio-,nioEventLoopGroup-,epollEventLoopGroup-")
        String prefixes();

        @WithDefault("sun.nio.ch.EPoll.wait,sun.nio.ch.EPollSelectorImpl.doSelect,io.netty.channel.epoll.Native.epollWait,io.netty.channel.nio.NioEventLoop.select")
        String selectorFrames();
    }

    interface PinningBurst {
        @WithDefault("10")
        int minCount();

        @WithDefault("10")
        double warningPct();

        @WithDefault("50")
        double criticalPct();
    }

    interface TtspSpike {
        @WithDefault("50")
        double warningMs();

        @WithDefault("500")
        double criticalMs();

        @WithDefault("5")
        double p99Multiple();

        @WithDefault("50")
        long minSafepoints();

        @WithDefault("60")
        int minWindows();
    }

    interface SafepointOverhead {
        @WithDefault("5")
        double warningPct();

        @WithDefault("15")
        double criticalPct();
    }

    interface DeoptStorm {
        @WithDefault("20")
        double warningPerS();

        @WithDefault("4")
        double z();

        @WithDefault("50")
        int criticalCount();

        @WithDefault("jdk.jfr.internal.,io.jafra.")
        String selfFrames();
    }

    interface JitBacklog {
        @WithDefault("500")
        double queueSize();
    }

    interface ClassLoadBurst {
        @WithDefault("100")
        double perS();

        @WithDefault("4")
        double z();

        @WithDefault("600")
        int minUptimeS();
    }

    interface ClassUnloadBurst {
        @WithDefault("100")
        double perS();
    }

    interface SlowPeer {
        @WithDefault("0.5")
        double warningThreadS();

        @WithDefault("2")
        double criticalThreadS();

        @WithDefault("4")
        double z();
    }

    interface TlsChurn {
        @WithDefault("20")
        double perS();
    }

    interface SlowFileIo {
        @WithDefault("0.2")
        double warningThreadS();

        @WithDefault("1")
        double criticalThreadS();

        @WithDefault("(?i)(\\.log$|/logs?/)")
        String logPath();
    }

    interface ExceptionStorm {
        @WithDefault("1000")
        double warningPerS();

        @WithDefault("4")
        double z();

        @WithDefault("10")
        double fillPct();

        @WithDefault("200")
        int minSamples();
    }

    interface EndpointSlowdown {
        @WithDefault("20")
        double minCalls();

        @WithDefault("2")
        double warningMultiple();

        @WithDefault("4")
        double z();

        @WithDefault("10")
        double criticalMaxMultiple();

        @WithDefault("3")
        double criticalAvgMultiple();
    }

    interface SlowRequests {
        @WithDefault("5")
        double warningPct();

        @WithDefault("25")
        double criticalPct();

        @WithDefault("5")
        int minSlow();

        @WithDefault("500")
        double defaultThresholdMs();
    }
}
