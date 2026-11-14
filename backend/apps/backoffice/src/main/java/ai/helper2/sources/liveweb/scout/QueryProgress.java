package ai.helper2.sources.liveweb.scout;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class QueryProgress {

  static final String PREFIX = "queries-done:";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private QueryProgress() {}

  static Set<String> done(@Nullable String resumeCursor) {
    if (resumeCursor == null || !resumeCursor.startsWith(PREFIX)) {
      return new LinkedHashSet<>();
    }
    try {
      return new LinkedHashSet<>(
          JSON.readValue(
              resumeCursor.substring(PREFIX.length()), new TypeReference<List<String>>() {}));
    } catch (JacksonException e) {
      return new LinkedHashSet<>();
    }
  }

  static String cursor(Collection<String> done) {
    return PREFIX + JSON.writeValueAsString(List.copyOf(done));
  }
}
