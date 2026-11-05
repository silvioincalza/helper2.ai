package ai.helper2.catalog.internal;

import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.Found;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import ai.helper2.search.ListMatch;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;

/**
 * The Helper2 PostgreSQL catalog as a federated source. Two instances exist: the INDEXED index
 * (Scout-collected and previously observed resources) and the CURATED editorial seed.
 */
public class CatalogSearchSource implements SearchSource {

  private final SourceDescriptor descriptor;
  private final ResourceStore store;
  private final Set<DataOrigin> origins;
  private final Clock clock;

  CatalogSearchSource(
      SourceDescriptor descriptor, ResourceStore store, Set<DataOrigin> origins, Clock clock) {
    this.descriptor = descriptor;
    this.store = store;
    this.origins = origins;
    this.clock = clock;
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    Instant after =
        query.getPublishedAfter() == null
            ? null
            : query.getPublishedAfter().atStartOfDay(ZoneOffset.UTC).toInstant();
    try {
      if (query.getBrowse()) {
        return after == null ? List.of() : hits(descriptor, browse(query, after), clock.instant());
      }
      return foundHits(textSearch(query, after));
    } catch (DataAccessException e) {
      throw new SourceFailure.Unavailable("catalog unavailable");
    }
  }

  private List<ResourceRow> browse(SourceQuery query, Instant after) {
    return store.browse(
        after,
        clock.instant(),
        query.getTypes().stream().map(Enum::name).collect(Collectors.toSet()),
        origins,
        query.getLimit());
  }

  private List<Found> textSearch(SourceQuery query, Instant after) {
    return store.searchFound(
        query.getText(),
        query.getRequiredVariants(),
        query.getExpandedTerms(),
        query.getSynonyms(),
        query.getLanguage(),
        origins,
        query.getLimit(),
        after);
  }

  /** Text search hits, each with where it was found when only an awesome list matched it. */
  private List<SourceHit> foundHits(List<Found> found) {
    List<SourceHit> hits =
        hits(descriptor, found.stream().map(Found::row).toList(), clock.instant());
    Map<String, ListMatch> foundIn = new HashMap<>();
    found.forEach(f -> {
      if (f.foundIn() != null) {
        foundIn.put(f.row().id().toString(), f.foundIn());
      }
    });
    if (foundIn.isEmpty()) {
      return hits;
    }
    return hits.stream()
        .map(h ->
            foundIn.containsKey(h.getExternalId())
                ? withFoundIn(h, foundIn.get(h.getExternalId()))
                : h)
        .toList();
  }

  private static SourceHit withFoundIn(SourceHit h, ListMatch match) {
    return new SourceHit(
        h.getSourceId(),
        h.getExternalId(),
        h.getUrl(),
        h.getCanonicalUrl(),
        h.getRepositoryUrl(),
        h.getTitle(),
        h.getDescription(),
        h.getDescriptionOrigin(),
        h.getType(),
        h.getAudience(),
        h.getTechLevel(),
        h.getUseCases(),
        h.getTags(),
        h.getLicense(),
        h.getPricing(),
        h.getPlatforms(),
        h.getOpenSource(),
        h.getPublished(),
        h.getUpdated(),
        h.getPopularity(),
        h.getRetrievedAt(),
        h.getRank(),
        h.getEventStart(),
        h.getEventEnd(),
        h.getLocation(),
        h.getEventKind(),
        h.getEventFormat(),
        h.getFacts(),
        match);
  }

  /**
   * Catalog rows as hits of [descriptor], ranked in row order; rows without a canonical URL are
   * skipped.
   */
  static List<SourceHit> hits(SourceDescriptor descriptor, List<ResourceRow> rows, Instant now) {
    List<SourceHit> hits = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      ResourceRow r = rows.get(i);
      CanonicalUrl canonical = CanonicalUrl.of(r.canonicalUrl());
      if (canonical == null) {
        continue;
      }
      hits.add(
          new SourceHit(
              descriptor.getId(),
              r.id().toString(),
              URI.create(r.url()),
              canonical,
              null,
              r.title(),
              r.description(),
              r.descriptionOrigin(),
              r.type(),
              r.audience(),
              r.techLevel(),
              r.useCases(),
              r.tags(),
              r.license(),
              r.pricing(),
              r.platforms(),
              r.openSource(),
              fact(r.publishedAt(), r.publishedPrecision(), r.publishedSourceId()),
              fact(r.updatedAt(), DatePrecision.INSTANT, r.updatedSourceId()),
              // Metrics keep their original source, like dates: the index only relays them.
              r.popularity(),
              now,
              i,
              DateFact.unknown(),
              DateFact.unknown(),
              null,
              null,
              null,
              Map.of(),
              // Assigned by the catalog (ADR 0053), so a search can filter and count them.
              r.categories()));
    }
    return hits;
  }

  /** Dates keep their ORIGINAL source attribution; the index only relays them. */
  private static DateFact fact(Instant value, DatePrecision precision, String sourceId) {
    if (value == null) {
      return DateFact.unknown();
    }
    return new DateFact(
        value,
        precision == DatePrecision.UNKNOWN ? DatePrecision.INSTANT : precision,
        sourceId,
        List.of(),
        false);
  }
}
