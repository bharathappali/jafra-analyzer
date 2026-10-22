package io.jafra.analyzer.v2.tools;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** UI context sent before a question. Chart and selection are null for free text. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ContextBlock(
        Jvm jvm, Range range, Chart chart, Selection selection, String summary, List<OpenIncident> openIncidents) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Jvm(String ns, String pod, String container, String start, Integer jdk) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Range(String from, String to) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Chart(
            String id, String title, String view, List<String> metrics, List<String> events, List<String> frames) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Selection(String kind) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OpenIncident(String id, String headline) {}
}
