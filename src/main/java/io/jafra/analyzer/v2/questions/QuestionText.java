package io.jafra.analyzer.v2.questions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;

/** Fills catalogue placeholders. Unknown names become empty. Range brushes keep {from} and {to}. */
public final class QuestionText {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private QuestionText() {}

    public static PersonaQuestions incident(QuestionsCatalogue catalogue, IncidentRecord row, String pod) {
        Map<String, String> values = incidentValues(row, pod);
        return new PersonaQuestions(
                fill(catalogue.incident(row.type(), "developer"), values, false),
                fill(catalogue.incident(row.type(), "expert"), values, false));
    }

    public static ObjectNode incidentNode(QuestionsCatalogue catalogue, IncidentRecord row, String pod) {
        return node(incident(catalogue, row, pod));
    }

    public static PersonaQuestions chart(QuestionsCatalogue catalogue, String chartId, String title, String metric) {
        Map<String, String> values = chartValues(title, metric);
        return new PersonaQuestions(
                fill(catalogue.chart(chartId, "developer"), values, false),
                fill(catalogue.chart(chartId, "expert"), values, false));
    }

    /** {from} and {to} stay in the text. The UI fills them from the brush as HH:MM:SS UTC. */
    public static PersonaQuestions range(QuestionsCatalogue catalogue, String chartId, String title, String metric) {
        Map<String, String> values = chartValues(title, metric);
        return new PersonaQuestions(
                fill(catalogue.range(chartId, "developer"), values, true),
                fill(catalogue.range(chartId, "expert"), values, true));
    }

    public static ObjectNode node(PersonaQuestions questions) {
        ObjectNode node = JSON.createObjectNode();
        node.set("developer", strings(questions.developer()));
        node.set("expert", strings(questions.expert()));
        return node;
    }

    static String clock(String rfc3339) {
        if (rfc3339 == null || rfc3339.length() < 19) {
            return "";
        }
        return rfc3339.substring(11, 19);
    }

    private static Map<String, String> incidentValues(IncidentRecord row, String pod) {
        String t0 = clock(V2Times.text(row.t0()));
        String t1 = row.t1() == null ? t0 : clock(V2Times.text(row.t1()));
        String headline = row.headline() == null ? "" : row.headline();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("t0", t0);
        values.put("t1", t1);
        values.put("headline", headline);
        values.put("who", who(row.whoJson()));
        values.put("pod", pod == null ? "" : pod);
        values.put("peak", peak(row.howBadJson(), headline));
        return values;
    }

    private static Map<String, String> chartValues(String title, String metric) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", title == null ? "" : title);
        values.put("metric", metric == null ? "" : metric);
        return values;
    }

    private static String who(String whoJson) {
        JsonNode who = tree(whoJson);
        if (who.isObject() && who.hasNonNull("name")) {
            String name = who.get("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }
        return "the hot frame";
    }

    private static String peak(String howBadJson, String headline) {
        JsonNode worst = tree(howBadJson).path("evidence").path("worst");
        JsonNode value = worst.get("value");
        JsonNode unit = worst.get("unit");
        if (value != null && !value.isNull() && unit != null && !unit.isNull() && !unit.asText("").isBlank()) {
            return value.asText() + " " + unit.asText();
        }
        if (headline != null) {
            Matcher number = NUMBER.matcher(headline);
            if (number.find()) {
                return number.group();
            }
        }
        return "";
    }

    private static List<String> fill(List<String> templates, Map<String, String> values, boolean keepBrush) {
        List<String> filled = new ArrayList<>();
        for (String template : templates) {
            filled.add(fillOne(template, values, keepBrush));
        }
        return List.copyOf(filled);
    }

    private static String fillOne(String template, Map<String, String> values, boolean keepBrush) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement;
            if (keepBrush && ("from".equals(name) || "to".equals(name))) {
                replacement = "{" + name + "}";
            } else {
                replacement = values.getOrDefault(name, "");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (IOException error) {
            return JSON.createObjectNode();
        }
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = JSON.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
