package ai.helper2.examples.usecase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Expected {

  private Expected() {}

  static List<String> unknown(List<UUID> expected, Map<UUID, String> known) {
    return expected.stream()
        .filter(id -> !known.containsKey(id))
        .map(id -> "expected result " + id + " is not in the catalog")
        .toList();
  }
}
