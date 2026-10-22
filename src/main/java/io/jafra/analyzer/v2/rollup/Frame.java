package io.jafra.analyzer.v2.rollup;

/** One stack frame, callee first when stored in a list. */
public record Frame(String name, String category, String type, int line) {}
