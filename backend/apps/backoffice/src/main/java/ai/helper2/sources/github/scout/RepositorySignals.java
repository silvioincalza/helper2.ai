package ai.helper2.sources.github.scout;

import ai.helper2.scout.CandidateSignals;
import ai.helper2.sources.support.HttpClients;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

final class RepositorySignals {

  private RepositorySignals() {}

  static CandidateSignals of(JsonNode repository) {
    JsonNode stars = repository.path("stargazers_count");
    return new CandidateSignals(
        stars.isIntegralNumber() ? stars.asLong() : null,
        HttpClients.instant(repository, "pushed_at"),
        flag(repository.path("archived")),
        flag(repository.path("fork")));
  }

  private static @Nullable Boolean flag(JsonNode stated) {
    return stated.isBoolean() ? stated.asBoolean() : null;
  }
}
