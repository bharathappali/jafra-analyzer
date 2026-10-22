package io.jafra.analyzer.v2.incidents;

/** Runs after every detector and before the lifecycle. Never lowers a state and never creates a verdict. */
public interface TogetherRule {
    void apply(TogetherContext context);
}
