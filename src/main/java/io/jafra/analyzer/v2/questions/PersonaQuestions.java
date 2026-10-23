package io.jafra.analyzer.v2.questions;

import java.util.List;

/** Developer and expert question lines for one incident, chart, or starter set. */
public record PersonaQuestions(List<String> developer, List<String> expert) {}
