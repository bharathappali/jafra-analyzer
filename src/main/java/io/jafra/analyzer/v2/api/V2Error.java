package io.jafra.analyzer.v2.api;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Every v2 error body has all four keys. {@code valid} is always an array. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record V2Error(String error, String message, String hint, List<String> valid) {
    public V2Error {
        valid = valid == null ? List.of() : List.copyOf(valid);
        hint = hint == null ? "" : hint;
    }
}
