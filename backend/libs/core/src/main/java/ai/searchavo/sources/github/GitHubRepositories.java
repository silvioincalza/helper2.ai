package ai.searchavo.sources.github;

import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.PopularityMetric;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.Vitality;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.support.Classification;
import ai.searchavo.sources.support.HttpClients;
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
            name,
            Classification.typeOf(name, description, topics, ResourceType.LIBRARY),
            now)
        .withDescription(description)
        .withAudience(Audience.BUILD_WITH_AI)
        .withTags(topics)
        .withLicense(license, Classification.openSource(license))
        .withPublished(
            DateFact.fromSource(HttpClients.instant(repository, "created_at"), sourceId, now))
        .withUpdated(
            DateFact.fromSource(HttpClients.instant(repository, "pushed_at"), sourceId, now))
        .withPopularity(popularity)
        .withRank(position)
        .withVitality(
            new Vitality(
                repository.path("archived").asBoolean(false),
                repository.path("fork").asBoolean(false)));
  }
}
