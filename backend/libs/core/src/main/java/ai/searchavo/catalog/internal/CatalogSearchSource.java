package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.ResourceRow;
import ai.searchavo.catalog.internal.JdbcCatalogSearch.Found;
import ai.searchavo.search.ListMatch;
import ai.searchavo.search.SearchSource;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.search.Vitality;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
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
                ? h.withFoundIn(foundIn.get(h.getExternalId()))
                : h)
        .toList();
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
                  r.title(),
                  r.type(),
                  now)
              .withDescription(r.description(), r.descriptionOrigin())
              .withAudience(r.audience())
              .withTechLevel(r.techLevel())
              .withUseCases(r.useCases())
              .withTags(r.tags())
              .withLicense(r.license(), r.openSource())
              .withPricing(r.pricing())
              .withPlatforms(r.platforms())
              .withPublished(fact(r.publishedAt(), r.publishedPrecision(), r.publishedSourceId()))
              .withUpdated(fact(r.updatedAt(), DatePrecision.INSTANT, r.updatedSourceId()))
              .withPopularity(r.popularity())
              .withRank(i)
              .withEventDays(
                  eventDay(r.eventStartsAt(), event.starts()),
                  eventDay(r.eventEndsAt(), event.ends()))
              .withLocation(event.location())
              .withEventKind(event.kind())
              .withEventFormat(event.format())
              .withCategories(r.categories())
              .withCuratedFields(r.curatedFields())
              .withVitality(stated.getOrDefault(r.id(), Vitality.UNKNOWN)));
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
