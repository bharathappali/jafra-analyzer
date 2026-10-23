package io.jafra.analyzer.v2.questions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Immutable question templates loaded once from questions.json. Templates still contain placeholders. */
@ApplicationScoped
public class QuestionsCatalogue {
    public static final String RESOURCE = "/jafra/v2/questions.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    public QuestionsCatalogue() {
        this(readResource());
    }

    QuestionsCatalogue(JsonNode root) {
        this.root = root;
    }

    public String version() {
        return root.path("version").asText();
    }

    public List<String> incident(String type, String persona) {
        return texts(root.path("incidents").path(type).path(persona));
    }

    public List<String> chart(String id, String persona) {
        return texts(root.path("charts").path(id).path(persona));
    }

    public List<String> range(String id, String persona) {
        return texts(root.path("charts").path(id).path("range_questions").path(persona));
    }

    public PersonaQuestions starters() {
        return new PersonaQuestions(starters("developer"), starters("expert"));
    }

    public List<String> starters(String persona) {
        return texts(root.path("starters").path(persona));
    }

    public Set<String> incidentTypes() {
        return keys(root.path("incidents"));
    }

    public Set<String> chartIds() {
        return keys(root.path("charts"));
    }

    private static JsonNode readResource() {
        try (InputStream in = QuestionsCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing questions catalogue " + RESOURCE);
            }
            return MAPPER.readTree(in);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<String> texts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) {
            values.add(child.asText(""));
        }
        return List.copyOf(values);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return Set.copyOf(names);
    }
}
