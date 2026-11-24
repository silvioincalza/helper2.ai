package ai.searchavo.research.internal;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ToolArgs {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ToolArgs() {}

  static @Nullable String text(String arguments, String field) {
    try {
      JsonNode v =
          JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments).path(field);
      if (!v.isString()) {
        return null;
      }
      String s = v.asString().strip();
      return s.isEmpty() || s.length() > 2000 ? null : s;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
