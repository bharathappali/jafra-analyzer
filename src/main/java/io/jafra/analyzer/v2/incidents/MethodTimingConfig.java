package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Copies jafra.v2.detect.method-timing.mode into {@link MethodTimingDelta}. */
@ApplicationScoped
public class MethodTimingConfig {
    @Inject
    void apply(@ConfigProperty(name = "jafra.v2.detect.method-timing.mode", defaultValue = "AUTO") String mode) {
        MethodTimingDelta.setMode(MethodTimingDelta.Mode.valueOf(mode.trim().toUpperCase()));
    }
}
