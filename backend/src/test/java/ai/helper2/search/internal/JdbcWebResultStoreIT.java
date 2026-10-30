package ai.helper2.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.CachedHits;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.search.IntentClass;
import ai.helper2.search.ResultObserver;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.usecase.PurgeExpiredData;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Stored Tavily result sets on the real schema: keyed by an HMAC of the query, hits read back from the catalog. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcWebResultStoreIT {

    @Autowired
    ResourceStore catalog;

    @Autowired
    ResultObserver observer;

    @Autowired
    JdbcClient jdbc;

    private JdbcWebResultStore store;

    @BeforeEach
    void store() {
        store = new JdbcWebResultStore(jdbc, new QuotaKeys("test-secret", Clock.systemUTC()), observer);
    }

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private SourceHit hit(String path, int rank) {
        String url = "https://news.example/" + run + "/" + path;
        return new SourceHit("tavily", CanonicalUrl.of(url).getValue(), URI.create(url), CanonicalUrl.of(url), null,
            "AI news " + path, "An AI agent framework launched", ValueOrigin.SOURCE_PROVIDED, ResourceType.NEWS, Audience.BOTH, null,
            Set.of(), Set.of(), null, null, Set.of(), null, DateFact.unknown(), DateFact.unknown(), List.of(), now, rank);
    }

    /** What the background observation of a live search writes. */
    private void recordInCatalog(SourceHit... hits) {
        SourceDescriptor tavily = new SourceDescriptor("tavily", "Tavily", SourceMode.LIVE, URI.create("https://tavily.com"), null, null,
            EnumSet.allOf(ResourceType.class), Duration.ofSeconds(6), Duration.ZERO);
        var merged = new ResultMerger().merge(List.of(hits), Map.of("tavily", new SourceContext(tavily, ServedFrom.LIVE)));
        catalog.upsert(merged, DataOrigin.OBSERVED, Set.of("tavily"));
    }

    @Test
    void aStoredSetComesBackInItsOrderFromTheCatalogRecords() {
        SourceHit first = hit("b", 0);
        SourceHit second = hit("a", 1);
        recordInCatalog(first, second);

        store.replace("tavily", "agents " + run, IntentClass.GENERAL, now, List.of(first, second));
        CachedHits found = store.find("tavily", "agents " + run, IntentClass.GENERAL);

        assertThat(found).isNotNull();
        assertThat(found.getFetchedAt()).isEqualTo(now);
        assertThat(found.getHits()).extracting(SourceHit::getTitle).containsExactly("AI news b", "AI news a");
        assertThat(found.getHits()).extracting(SourceHit::getRank).containsExactly(0, 1);
        assertThat(found.getHits()).allMatch(h -> h.getSourceId().equals("tavily"));
    }

    @Test
    void theKeyIsTheSourceTheQueryAndTheIntentClassExactly() {
        recordInCatalog(hit("a", 0));
        store.replace("tavily", "agents " + run, IntentClass.TIME_BOUND, now, List.of(hit("a", 0)));

        assertThat(store.find("tavily", "agents " + run, IntentClass.GENERAL)).isNull();
        assertThat(store.find("tavily", "agent " + run, IntentClass.TIME_BOUND)).isNull();
        assertThat(store.find("other", "agents " + run, IntentClass.TIME_BOUND)).isNull();
        assertThat(store.find("tavily", "agents " + run, IntentClass.TIME_BOUND)).isNotNull();
    }

    @Test
    void aNewLiveAnswerReplacesTheStoredSet() {
        recordInCatalog(hit("a", 0), hit("b", 0));
        store.replace("tavily", "agents " + run, IntentClass.GENERAL, now.minus(Duration.ofDays(3)), List.of(hit("a", 0)));
        store.replace("tavily", "agents " + run, IntentClass.GENERAL, now, List.of(hit("b", 0)));

        CachedHits found = store.find("tavily", "agents " + run, IntentClass.GENERAL);

        assertThat(found.getFetchedAt()).isEqualTo(now);
        assertThat(found.getHits()).extracting(SourceHit::getTitle).containsExactly("AI news b");
    }

    @Test
    void aSetWithAHitTheCatalogDoesNotHoldIsAMissNotAPartialAnswer() {
        recordInCatalog(hit("a", 0));
        store.replace("tavily", "agents " + run, IntentClass.GENERAL, now, List.of(hit("a", 0), hit("not-recorded", 1)));

        assertThat(store.find("tavily", "agents " + run, IntentClass.GENERAL)).isNull();
    }

    @Test
    void anEmptySetIsStoredAndFound() {
        store.replace("tavily", "nothing " + run, IntentClass.GENERAL, now, List.of());

        assertThat(store.find("tavily", "nothing " + run, IntentClass.GENERAL).getHits()).isEmpty();
    }

    @Test
    void noQueryTextIsStored() {
        store.replace("tavily", "secret plans " + run, IntentClass.GENERAL, now, List.of());

        List<byte[]> keys = jdbc.sql("SELECT query_key FROM tavily_query_result").query(byte[].class).list();
        assertThat(keys).allSatisfy(k -> assertThat(new String(k, StandardCharsets.ISO_8859_1)).doesNotContain("secret plans"));
        assertThat(keys).allSatisfy(k -> assertThat(k).hasSize(32));
    }

    @Test
    void setsFetchedMoreThanSevenDaysAgoArePurged() {
        Instant purgeNow = Instant.parse("2000-07-01T00:00:00Z");
        store.replace("tavily", "old " + run, IntentClass.GENERAL, purgeNow.minus(Duration.ofDays(8)), List.of());
        store.replace("tavily", "recent " + run, IntentClass.GENERAL, purgeNow.minus(Duration.ofDays(6)), List.of());

        int deleted = new PurgeExpiredData(store, Clock.fixed(purgeNow, ZoneOffset.UTC), Duration.ofDays(7)).purge();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.find("tavily", "old " + run, IntentClass.GENERAL)).isNull();
        assertThat(store.find("tavily", "recent " + run, IntentClass.GENERAL)).isNotNull();
        jdbc.sql("DELETE FROM tavily_query_result WHERE fetched_at < '2001-01-01'").update();
    }
}
