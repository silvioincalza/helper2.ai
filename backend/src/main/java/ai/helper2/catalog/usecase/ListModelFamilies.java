package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceDetail;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * AI model families for the comparison page. A family is a curated catalog resource (its official
 * models page) whose specs are curated field assertions, each with the provider page, a verbatim
 * excerpt and the date it was read. Next to them, the provider's newest models as Scout sources
 * listed them; the two are never merged.
 */
@Service
public class ListModelFamilies {

    /** Sources whose records carry the provider as a tag (the Hub organisation). */
    static final List<String> LISTING_SOURCES = List.of("hf-provider-orgs");

    public record ListedModel(UUID resourceId, String title, URI url, String sourceId, Instant retrievedAt,
                              @Nullable Instant published) {
    }

    public record Family(String id, String provider, String name, @Nullable Boolean openWeights,
                         ResourceDetail detail, List<ListedModel> listed) {
    }

    private record Row(String id, String provider, String name, @Nullable Boolean openWeights, UUID resourceId, List<String> tags) {
    }

    private final JdbcClient jdbc;
    private final GetResourceDetail details;

    public ListModelFamilies(JdbcClient jdbc, GetResourceDetail details) {
        this.jdbc = jdbc;
        this.details = details;
    }

    public List<Family> list(int listedPerFamily) {
        List<Row> rows = jdbc.sql("SELECT id, provider, name, open_weights, resource_id, provider_tags FROM model_family ORDER BY position")
            .query((rs, n) -> new Row(rs.getString("id"), rs.getString("provider"), rs.getString("name"),
                (Boolean) rs.getObject("open_weights"), rs.getObject("resource_id", UUID.class),
                Arrays.asList((String[]) rs.getArray("provider_tags").getArray())))
            .list();
        List<Family> out = new ArrayList<>();
        for (Row r : rows) {
            details.find(r.resourceId()).ifPresent(d ->
                out.add(new Family(r.id(), r.provider(), r.name(), r.openWeights(), d, listed(r.tags(), listedPerFamily))));
        }
        return out;
    }

    private List<ListedModel> listed(List<String> tags, int limit) {
        if (tags.isEmpty() || limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT r.id, sr.title, sr.source_url, sr.source_id, sr.retrieved_at, sr.source_published_at
                FROM source_record sr JOIN resource r ON r.id = sr.resource_id
                WHERE r.type = 'MODEL' AND sr.source_id IN (:sources)
                  AND EXISTS (SELECT 1 FROM resource_label l WHERE l.resource_id = r.id AND l.kind = 'TAG' AND l.value IN (:tags))
                ORDER BY sr.source_published_at DESC NULLS LAST, sr.title
                LIMIT :limit
                """)
            .param("sources", LISTING_SOURCES).param("tags", tags).param("limit", limit)
            .query((rs, n) -> new ListedModel(rs.getObject("id", UUID.class), rs.getString("title"),
                URI.create(rs.getString("source_url")), rs.getString("source_id"), rs.getTimestamp("retrieved_at").toInstant(),
                instant(rs.getTimestamp("source_published_at"))))
            .list();
    }

    private static @Nullable Instant instant(@Nullable Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
