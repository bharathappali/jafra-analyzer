package io.jafra.analyzer.v2.incidents;

/**
 * Who is affected. {@code kind} is thread, class, lock, site, handler, loader, or interface.
 */
public record Who(String kind, String name, Double sharePct, boolean estimate) {}
