package io.jafra.analyzer.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class SignatureTest {
    @Test
    void theCatalogueListsEveryDetector() throws Exception {
        List<String> ids = ids(Files.readString(Path.of("src/main/resources/signatures.yaml")));
        assertEquals(21, ids.size());
        assertTrue(ids.contains("HEALTHY"));
        assertTrue(ids.contains("PREMATURE_PROMOTION"));
        assertTrue(ids.contains("ERGONOMIC_SERIAL_GC"));
        WindowSnapshot quiet = new WindowSnapshot();
        quiet.observed = true;
        quiet.windowNs = 60_000_000_000L;
        assertEquals("HEALTHY", FindingDetectors.evaluate(quiet).get(0).get("code"));
    }

    @Test
    @EnabledIf("scenarioFixturesPresent")
    void scenarioFixturesMatchTheirSignatures() {
        assertTrue(Files.isDirectory(Path.of("src/test/resources/jfr-fixtures/scenarios")));
    }

    static boolean scenarioFixturesPresent() {
        return Files.isDirectory(Path.of("src/test/resources/jfr-fixtures/scenarios"));
    }

    private static List<String> ids(String yaml) {
        List<String> ids = new ArrayList<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- id:")) {
                ids.add(trimmed.substring("- id:".length()).strip());
            }
        }
        return ids;
    }
}
