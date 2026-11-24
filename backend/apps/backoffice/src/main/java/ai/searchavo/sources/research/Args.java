package ai.searchavo.sources.research;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class Args {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JsonNode node;

  private Args(JsonNode node) {
    this.node = node;
  }

  static Args of(String arguments) {
    try {
      JsonNode n = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      return new Args(n.isObject() ? n : JSON.createObjectNode());
    } catch (RuntimeException e) {
      return new Args(JSON.createObjectNode());
    }
  }

  @Nullable String text(String field) {
    JsonNode v = node.path(field);
    if (!v.isString()) {
      return null;
    }
    String s = v.asString().strip();
    return s.isEmpty() || s.length() > 500 ? null : s;
  }
}
