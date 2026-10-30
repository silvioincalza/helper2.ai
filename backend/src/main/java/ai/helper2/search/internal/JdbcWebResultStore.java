package ai.helper2.search.internal;

import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.search.CachedHits;
import ai.helper2.search.IntentClass;
import ai.helper2.search.ResultObserver;
import ai.helper2.search.SourceHit;
import ai.helper2.search.WebResultRetention;
import ai.helper2.search.WebResultStore;
import ai.helper2.shared.ResourceId;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link WebResultStore} in {@code tavily_query_result} (ADR 0037). The key is an HMAC of the source id and the query
 * ({@link QuotaKeys#queryKey}), so no query text is stored. A set holds the resource ids of its hits' own URLs; the hits
 * come back from the catalog's source records ({@link ResultObserver#recordedHits}), which every live search writes in
 * the background. A set with a hit the catalog does not hold (yet, or any more) is a miss, never a partial answer.
 */
public class JdbcWebResultStore implements WebResultStore, WebResultRetention {

    private final JdbcClient jdbc;
    private final QuotaKeys keys;
    private final ResultObserver catalog;

    public JdbcWebResultStore(JdbcClient jdbc, QuotaKeys keys, ResultObserver catalog) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.catalog = catalog;
    }

    private record Row(Instant fetchedAt, List<ResourceId> ids) {
    }

    @Override
    public CachedHits find(String sourceId, String query, IntentClass intentClass) {
        Row row = jdbc.sql("SELECT fetched_at, result_ids FROM tavily_query_result WHERE query_key = :key AND intent_class = :class")
            .param("key", key(sourceId, query)).param("class", intentClass.name())
            .query((rs, i) -> new Row(rs.getTimestamp("fetched_at").toInstant(), ids(rs.getArray("result_ids"))))
            .optional().orElse(null);
        if (row == null) {
            return null;
        }
        List<SourceHit> recorded = row.ids().isEmpty() ? List.of() : catalog.recordedHits(sourceId, row.ids());
        if (recorded.size() < row.ids().size()) {
            return null;
        }
        List<SourceHit> hits = new ArrayList<>(recorded.size());
        for (int i = 0; i < recorded.size(); i++) {
            hits.add(recorded.get(i).withRank(i));
        }
        return new CachedHits(hits, row.fetchedAt());
    }

    @Override
    public void replace(String sourceId, String query, IntentClass intentClass, Instant fetchedAt, List<SourceHit> hits) {
        UUID[] ids = hits.stream().map(h -> ResourceId.of(h.getCanonicalUrl()).getValue()).distinct().toArray(UUID[]::new);
        jdbc.sql("""
                INSERT INTO tavily_query_result (query_key, intent_class, fetched_at, result_ids)
                VALUES (:key, :class, :fetchedAt, :ids)
                ON CONFLICT (query_key, intent_class) DO UPDATE SET fetched_at = EXCLUDED.fetched_at, result_ids = EXCLUDED.result_ids
                """)
            .param("key", key(sourceId, query)).param("class", intentClass.name())
            .param("fetchedAt", Timestamp.from(fetchedAt)).param("ids", ids)
            .update();
    }

    @Override
    public int deleteFetchedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM tavily_query_result WHERE fetched_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }

    private byte[] key(String sourceId, String query) {
        return keys.queryKey(sourceId + "|" + query);
    }

    private static List<ResourceId> ids(Array array) throws java.sql.SQLException {
        return Arrays.stream((UUID[]) array.getArray()).map(ResourceId::new).toList();
    }
}
