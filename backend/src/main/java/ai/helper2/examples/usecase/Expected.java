package ai.helper2.examples.usecase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Expected results must be resources the catalog has: the check could never find another. */
final class Expected {

  private Expected() {}

  /** One problem per expected id the catalog does not know. */
  static List<String> unknown(List<UUID> expected, Map<UUID, String> known) {
    return expected.stream()
        .filter(id -> !known.containsKey(id))
        .map(id -> "expected result " + id + " is not in the catalog")
        .toList();
  }
}
