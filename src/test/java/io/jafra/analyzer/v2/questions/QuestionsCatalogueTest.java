package io.jafra.analyzer.v2.questions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.incidents.Detector;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class QuestionsCatalogueTest {
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final Set<String> INCIDENT_TOKENS = Set.of(
            "t0", "t1", "headline", "who", "pod", "peak", "type_title");
    private static final Set<String> RANGE_TOKENS = Set.of("from", "to", "title", "metric");

    @Inject
    Instance<Detector> detectors;

    @Inject
    Catalogue catalogue;

    @Inject
    QuestionsCatalogue questions;

    @Test
    void everyDetectorAndChartHasThreeQuestionsPerPersona() {
        Set<String> types = new HashSet<>();
        for (Detector detector : detectors) {
            assertTrue(types.add(detector.type()), detector.type());
            assertPersona(questions.incident(detector.type(), "developer"), detector.type() + " developer");
            assertPersona(questions.incident(detector.type(), "expert"), detector.type() + " expert");
            assertTokens(questions.incident(detector.type(), "developer"), INCIDENT_TOKENS, detector.type());
            assertTokens(questions.incident(detector.type(), "expert"), INCIDENT_TOKENS, detector.type());
        }
        assertEquals(41, types.size());
        assertEquals(types, questions.incidentTypes());

        assertEquals(Set.copyOf(catalogue.chartIds()), questions.chartIds());
        for (String id : catalogue.chartIds()) {
            assertPersona(questions.chart(id, "developer"), id + " developer");
            assertPersona(questions.chart(id, "expert"), id + " expert");
            assertPersona(questions.range(id, "developer"), id + " range developer");
            assertPersona(questions.range(id, "expert"), id + " range expert");
            assertTokens(questions.range(id, "developer"), RANGE_TOKENS, id + " range");
            assertTokens(questions.range(id, "expert"), RANGE_TOKENS, id + " range");
        }
    }

    private static void assertPersona(List<String> lines, String label) {
        assertTrue(lines.size() >= 3, label + " has " + lines.size());
    }

    private static void assertTokens(List<String> lines, Set<String> allowed, String label) {
        for (String line : lines) {
            Matcher matcher = TOKEN.matcher(line);
            while (matcher.find()) {
                assertTrue(allowed.contains(matcher.group(1)), label + " token " + matcher.group(1) + " in " + line);
            }
        }
    }
}
