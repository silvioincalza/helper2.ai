package ai.helper2.sources.github;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class GitHubRepositories {

  private GitHubRepositories() {}

  public static Set<String> topics(JsonNode repository) {
    Set<String> topics = new LinkedHashSet<>();
    repository.path("topics").forEach(t -> topics.add(t.asString()));
    return topics;
  }

  public static @Nullable SourceHit hit(
      String sourceId, JsonNode repository, Instant now, int position) {
    String name = HttpClients.text(repository, "full_name");
    String htmlUrl = HttpClients.text(repository, "html_url");
    CanonicalUrl canonical = CanonicalUrl.of(htmlUrl);
    if (name == null || canonical == null) {
      return null;
    }
    String description = HttpClients.text(repository, "description");
    Set<String> topics = topics(repository);
    String spdx =
        repository.path("license").isObject()
            ? HttpClients.text(repository.path("license"), "spdx_id")
            : null;
    String license = spdx == null ? null : ResultMerger.normalizeLicense(spdx);
    List<PopularityMetric> popularity =
        repository.has("stargazers_count")
            ? List.of(
                new PopularityMetric(
                    PopularityKind.GITHUB_STARS,
                    repository.path("stargazers_count").asLong(),
                    sourceId,
                    now))
            : List.of();
    return new SourceHit(
            sourceId,
            name,
            URI.create(htmlUrl),
            canonical,
            null,
            name,
            description,
            ValueOrigin.SOURCE_PROVIDED,
            Classification.typeOf(name, description, topics, ResourceType.LIBRARY),
            Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            topics,
            license,
            null,
            Set.of(),
            Classification.openSource(license),
            DateFact.fromSource(HttpClients.instant(repository, "created_at"), sourceId, now),
            DateFact.fromSource(HttpClients.instant(repository, "pushed_at"), sourceId, now),
            popularity,
            now,
            position)
        .withVitality(
            new Vitality(
                repository.path("archived").asBoolean(false),
                repository.path("fork").asBoolean(false)));
  }
}
