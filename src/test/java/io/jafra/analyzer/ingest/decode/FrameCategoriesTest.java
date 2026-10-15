package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FrameCategoriesTest {
    @Test
    void classifiesKnownPrefixes() {
        assertEquals("framework", FrameCategories.of("io.quarkus.runtime.Application", "run", "main"));
        assertEquals("framework", FrameCategories.of("org.springframework.boot.SpringApplication", "run", "main"));
        assertEquals("framework", FrameCategories.of("io.netty.channel.nio.NioEventLoop", "run", "nio"));
        assertEquals("framework", FrameCategories.of("io.vertx.core.impl.VertxImpl", "run", "vert.x"));
        assertEquals("framework", FrameCategories.of("org.hibernate.Session", "flush", "main"));
        assertEquals("framework", FrameCategories.of("com.zaxxer.hikari.pool.HikariPool", "getConnection", "main"));
        assertEquals("jdk", FrameCategories.of("java.lang.String", "charAt", "main"));
        assertEquals("jdk", FrameCategories.of("jdk.internal.misc.Unsafe", "park", "main"));
        assertEquals("app", FrameCategories.of("com.acme.Cache", "get", "main"));
        assertEquals("profiler", FrameCategories.of("profiler.WallClock", "sample", "main"));
        assertEquals("gc", FrameCategories.of("", "GCTask", "G1 Main Marker"));
        assertEquals("jit", FrameCategories.of("", "compile", "C2 CompilerThread0"));
        assertEquals("vm", FrameCategories.of("", "loop", "VM Thread"));
        assertEquals("native", FrameCategories.of("", "clock_gettime", "main"));
    }
}
