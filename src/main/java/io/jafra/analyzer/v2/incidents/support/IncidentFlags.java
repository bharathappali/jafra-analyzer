package io.jafra.analyzer.v2.incidents.support;

/** Incident-only flags. They are stored on the incident, never on a metric result. */
public enum IncidentFlags {
    carried_forward,
    no_cpu_limit,
    no_memory_limit,
    known_gap_prealloc_oom,
    page_cache_included,
    netty_offheap_excluded,
    busy_loop_included,
    self_instrumentation,
    lock_formula_unverified,
    methodtiming_mode_inferred
}
