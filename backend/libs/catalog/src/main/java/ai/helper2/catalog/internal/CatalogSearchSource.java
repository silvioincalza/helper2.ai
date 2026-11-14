package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.catalog.internal.JdbcCatalogSearch.Found;
import ai.helper2.search.ListMatch;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.Vitality;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;

public class CatalogSearchSource implements SearchSource {

  private final SourceDescriptor descriptor;
  private final JdbcCatalogSearch store;
  private final Set<DataOrigin> origins;
  private final Clock clock;

  CatalogSearchSource(
      SourceDescriptor descriptor, JdbcCatalogSearch store, Set<DataOrigin> origins, Clock clock) {
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
        if (after == null) {
          return List.of();
        }
        List<ResourceRow> rows = browse(query, after);
        return hits(
            descriptor,
            rows,
            clock.instant(),
            store.statedVitality(rows),
            store.statedEventFacts(rows));
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

  private List<SourceHit> foundHits(List<Found> found) {
    List<ResourceRow> rows = found.stream().map(Found::row).toList();
    List<SourceHit> hits =
        hits(
            descriptor,
            rows,
            clock.instant(),
            store.statedVitality(rows),
            store.statedEventFacts(rows));
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
        h.getCategories(),
        match,
        h.getCuratedFields(),
        h.getVitality());
  }

  static List<SourceHit> hits(
      SourceDescriptor descriptor,
      List<ResourceRow> rows,
      Instant now,
      Map<UUID, Vitality> stated,
      Map<UUID, StatedEventFacts> statedEventFacts) {
    List<SourceHit> hits = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      ResourceRow r = rows.get(i);
      CanonicalUrl canonical = CanonicalUrl.of(r.canonicalUrl());
      if (canonical == null) {
        continue;
      }
      StatedEventFacts event = statedEventFacts.getOrDefault(r.id(), StatedEventFacts.UNKNOWN);
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
              r.popularity(),
              now,
              i,
              eventDay(r.eventStartsAt(), event.starts()),
              eventDay(r.eventEndsAt(), event.ends()),
              event.location(),
              event.kind(),
              event.format(),
              Map.of(),
              r.categories(),
              null,
              r.curatedFields(),
              stated.getOrDefault(r.id(), Vitality.UNKNOWN)));
    }
    return hits;
  }

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

  private static DateFact eventDay(Instant ofTheRow, DateFact stated) {
    if (ofTheRow == null) {
      return DateFact.unknown();
    }
    return new DateFact(
        ofTheRow,
        DatePrecision.DAY,
        stated.getSourceId(),
        stated.getAlternatives(),
        stated.getConflicting());
  }
}
