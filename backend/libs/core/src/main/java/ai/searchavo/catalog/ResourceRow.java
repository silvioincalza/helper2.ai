package ai.searchavo.catalog;

import ai.searchavo.search.PopularityMetric;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.Pricing;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.TechLevel;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.shared.WebPageTitle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ResourceRow(
    UUID id,
    String canonicalUrl,
    String url,
    String title,
    String description,
    ValueOrigin descriptionOrigin,
    ResourceType type,
    Audience audience,
    TechLevel techLevel,
    String license,
    Pricing pricing,
    Boolean openSource,
    Instant publishedAt,
    DatePrecision publishedPrecision,
    String publishedSourceId,
    Instant updatedAt,
    String updatedSourceId,
    Instant eventStartsAt,
    Instant eventEndsAt,
    Instant firstDiscoveredAt,
    DataOrigin dataOrigin,
    String enrichmentStatus,
    Set<String> tags,
    Set<String> useCases,
    Set<String> platforms,
    Set<String> categories,
    List<PopularityMetric> popularity,
    Set<String> curatedFields) {
  public ResourceRow withFacts(Map<String, Set<String>> labels, List<PopularityMetric> metrics) {
    return new ResourceRow(
        id,
        canonicalUrl,
        url,
        title,
        description,
        descriptionOrigin,
        type,
        audience,
        techLevel,
        license,
        pricing,
        openSource,
        publishedAt,
        publishedPrecision,
        publishedSourceId,
        updatedAt,
        updatedSourceId,
        eventStartsAt,
        eventEndsAt,
        firstDiscoveredAt,
        dataOrigin,
        enrichmentStatus,
        labels.getOrDefault("TAG", Set.of()),
        labels.getOrDefault("USE_CASE", Set.of()),
        labels.getOrDefault("PLATFORM", Set.of()),
        labels.getOrDefault("CATEGORY", Set.of()),
        metrics,
        curatedFields);
  }

  public ResourceRow withPageTitle() {
    String clean = pageTitle(title, canonicalUrl);
    return clean.equals(title)
        ? this
        : new ResourceRow(
            id,
            canonicalUrl,
            url,
            clean,
            description,
            descriptionOrigin,
            type,
            audience,
            techLevel,
            license,
            pricing,
            openSource,
            publishedAt,
            publishedPrecision,
            publishedSourceId,
            updatedAt,
            updatedSourceId,
            eventStartsAt,
            eventEndsAt,
            firstDiscoveredAt,
            dataOrigin,
            enrichmentStatus,
            tags,
            useCases,
            platforms,
            categories,
            popularity,
            curatedFields);
  }

  public static String pageTitle(String title, String canonicalUrl) {
    CanonicalUrl page = CanonicalUrl.of(canonicalUrl);
    return WebPageTitle.of(title, page == null ? null : page.getHost()).getTitle();
  }
}
