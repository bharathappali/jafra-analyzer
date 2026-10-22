package io.jafra.analyzer.v2.incidents;

/** Warning and critical bounds. Null means that level does not exist for the detector. */
public record Band(Double warning, Double critical) {}
