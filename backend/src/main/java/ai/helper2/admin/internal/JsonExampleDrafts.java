package ai.helper2.admin.internal;

import ai.helper2.admin.ExampleDrafts;
import ai.helper2.shared.AudiencePath;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the JSON draft DraftHomeExamples wrote; anything else is no draft, never a 500. */
@Component
class JsonExampleDrafts implements ExampleDrafts {

  private final ObjectMapper json;

  JsonExampleDrafts(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public Optional<Draft> read(@Nullable String details) {
    try {
      JsonNode d = json.readTree(details == null ? "" : details);
      return Optional.of(
          new Draft(
              text(d, "label"),
              text(d, "query"),
              AudiencePath.valueOf(text(d, "path")),
              text(d, "model")));
    } catch (JacksonException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String text(JsonNode d, String field) {
    JsonNode v = d.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new IllegalArgumentException(field);
    }
    return v.asString();
  }
}
