package io.jafra.analyzer.v2.rollup;

/** A 5 s window has been committed. Detectors run only when {@code late} is false. */
public record WindowCommitted(String chunkId, int jvmId, long fromMs, long toMs, boolean late) {}
