package io.jafra.analyzer.v2.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class ErrorBodyTest {
    @Test
    void everyCodeHasAllFourKeysAndAHint() {
        List<V2Exception> errors = List.of(
                V2Exception.unknownMetric("missing", List.of("cpu.used.cores")),
                V2Exception.unsupportedStat("no", List.of("avg")),
                V2Exception.unsupportedBy("no", List.of("class")),
                V2Exception.notOnJdk("needs JDK 25"),
                V2Exception.nmtOff(),
                V2Exception.noData("none", List.of("1970-01-01T00:00:00.000Z", "1970-01-01T00:00:05.000Z")),
                V2Exception.notEnough(3),
                V2Exception.unknownIncident(List.of("a")),
                V2Exception.badRange("from must be earlier than to"),
                V2Exception.unknownJvm(),
                V2Exception.ambiguous(List.of("a", "b")),
                V2Exception.notReady(),
                V2Exception.unknownChart(List.of("heap-used")));
        for (V2Exception error : errors) {
            V2Error body = error.body();
            assertNotNull(body.error());
            assertNotNull(body.message());
            assertNotNull(body.hint());
            assertFalse(body.hint().isBlank());
            assertNotNull(body.valid());
        }
    }
}
