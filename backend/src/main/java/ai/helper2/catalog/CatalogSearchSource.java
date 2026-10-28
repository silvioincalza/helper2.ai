package ai.helper2.catalog;

import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
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
import java.util.List;
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

    public CatalogSearchSource(SourceDescriptor descriptor, ResourceStore store, Set<DataOrigin> origins, Clock clock) {
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
        List<ResourceRow> rows;
        Instant after = query.getPublishedAfter() == null ? null : query.getPublishedAfter().atStartOfDay(ZoneOffset.UTC).toInstant();
        try {
            rows = query.getBrowse() && after != null
                ? store.browse(after, clock.instant(), query.getTypes().stream().map(Enum::name).collect(Collectors.toSet()), origins, query.getLimit())
                : query.getBrowse() ? List.of()
                : store.search(query.getText(), query.getRequiredVariants(), query.getExpandedTerms(), query.getSynonyms(),
                    query.getLanguage(), origins, query.getLimit(), after);
        } catch (DataAccessException e) {
            throw new SourceFailure.Unavailable("catalog unavailable");
        }
        return hits(descriptor, rows, clock.instant());
    }

    /** Catalog rows as hits of [descriptor], ranked in row order; rows without a canonical URL are skipped. */
    static List<SourceHit> hits(SourceDescriptor descriptor, List<ResourceRow> rows, Instant now) {
        List<SourceHit> hits = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ResourceRow r = rows.get(i);
            CanonicalUrl canonical = CanonicalUrl.of(r.canonicalUrl());
            if (canonical == null) {
                continue;
            }
            hits.add(new SourceHit(
                descriptor.getId(), r.id().toString(), URI.create(r.url()), canonical, null,
                r.title(), r.description(), r.descriptionOrigin(), r.type(), r.audience(), r.techLevel(),
                r.useCases(), r.tags(), r.license(), r.pricing(), r.platforms(), r.openSource(),
                fact(r.publishedAt(), r.publishedPrecision(), r.publishedSourceId()),
                fact(r.updatedAt(), DatePrecision.INSTANT, r.updatedSourceId()),
                // Metrics keep their original source, like dates: the index only relays them.
                r.popularity(), now, i));
        }
        return hits;
    }

    /** Dates keep their ORIGINAL source attribution; the index only relays them. */
    private static DateFact fact(Instant value, DatePrecision precision, String sourceId) {
        if (value == null) {
            return DateFact.unknown();
        }
        return new DateFact(value, precision == DatePrecision.UNKNOWN ? DatePrecision.INSTANT : precision,
            sourceId, List.of(), false);
    }
}
