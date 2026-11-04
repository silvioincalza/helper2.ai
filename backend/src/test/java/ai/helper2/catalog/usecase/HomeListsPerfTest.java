package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.MergedResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Latency of the home page lists on a catalog of about 25 000 resources and 200 events: the cards read one by one
 * (as before batching: one resource, then its records, per id) against the batched use cases. 200 calls each after
 * a warm-up; p50/p95/p99 go to stdout and build/perf/home-lists.txt. Run with ./gradlew perfTest (not part of test).
 */
@Tag("perf")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomeListsPerfTest {

    static final int RESOURCES = 25_000;
    static final int EVENTS = 200;
    static final int WARMUP = 30;
    static final int CALLS = 200;
    static final Instant SINCE = Instant.parse("2098-12-01T00:00:00Z");
    static final Instant UNTIL = Instant.parse("2099-01-01T00:00:00Z");
    static final Instant TODAY = Instant.parse("2099-01-01T00:00:00Z");
    static final String PREFIX = "https://perf.example/home-lists/";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ResourceStore store;

    @Autowired
    ResourceCards cards;

    @Autowired
    ListRecentResources recent;

    @Autowired
    ListUpcomingEvents upcoming;

    final List<String> report = new ArrayList<>();

    @BeforeAll
    void seed() {
        jdbc.sql("""
                INSERT INTO source (id, name, mode, homepage) VALUES ('github', 'GitHub', 'INDEXED', 'https://github.com')
                ON CONFLICT DO NOTHING
                """).update();
        // Resources 1..200 are events (one a day from TODAY); the rest are published in the month before UNTIL.
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
                                      data_origin, published_at, published_precision, published_source_id,
                                      event_starts_at, event_ends_at)
                SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, :prefix || g), :prefix || g, :prefix || g,
                       'Perf resource ' || g, repeat('Words about resource ' || g || '. ', 1 + g % 8), 'SOURCE_PROVIDED',
                       CASE WHEN g <= :events THEN 'EVENT' ELSE 'LIBRARY' END, 'BUILD_WITH_AI',
                       CASE WHEN g % 4 = 0 THEN 'OBSERVED' ELSE 'INDEXED' END,
                       CAST(:until AS timestamptz) - g * interval '100 seconds', 'INSTANT', 'github',
                       CASE WHEN g <= :events THEN CAST(:today AS timestamptz) + g * interval '1 day' END,
                       CASE WHEN g <= :events THEN CAST(:today AS timestamptz) + (g + 2) * interval '1 day' END
                FROM generate_series(1, :n) g
                """)
            .param("prefix", PREFIX).param("events", EVENTS).param("n", RESOURCES)
            .param("until", UNTIL.toString()).param("today", TODAY.toString()).update();
        jdbc.sql("""
                INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                           description, type, retrieved_at, fingerprint, extra)
                SELECT gen_random_uuid(), 'github', r.canonical_url || '#' || k, r.id, r.url, r.canonical_url, r.title,
                       r.description, r.type, now(), md5(r.id::text || k),
                       jsonb_build_object('audience', 'BUILD_WITH_AI', 'tags', jsonb_build_array('mcp', 'python', 'agents'),
                           'popularity', jsonb_build_array(jsonb_build_object('kind', 'GITHUB_STARS',
                               'value', 10 + (abs(hashtext(r.id::text)) % 50000), 'asOf', '2026-10-01T00:00:00Z')))
                FROM resource r, generate_series(1, 2) k
                WHERE r.canonical_url LIKE :prefix || '%' AND (k = 1 OR r.type = 'LIBRARY' AND abs(hashtext(r.id::text)) % 3 = 0)
                """).param("prefix", PREFIX).update();
        jdbc.sql("""
                INSERT INTO resource_label (resource_id, kind, value)
                SELECT r.id, 'TAG', t FROM resource r, unnest(ARRAY['mcp', 'python', 'agents']) t
                WHERE r.canonical_url LIKE :prefix || '%'
                """).param("prefix", PREFIX).update();
        jdbc.sql("ANALYZE resource").update();
        jdbc.sql("ANALYZE source_record").update();
        jdbc.sql("ANALYZE resource_label").update();
    }

    @AfterAll
    void cleanAndWrite() throws IOException {
        jdbc.sql("DELETE FROM resource WHERE canonical_url LIKE :prefix || '%'").param("prefix", PREFIX).update();
        Path out = Path.of("build/perf/home-lists.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
    }

    /** The cards as the home lists read them before batching: one resource, then its records, per id. */
    private List<MergedResult> oneByOne(List<UUID> ids) {
        List<MergedResult> out = new ArrayList<>();
        for (UUID id : ids) {
            store.findResource(id).flatMap(row -> cards.card(row, store.recordsOf(id))).ifPresent(out::add);
        }
        return out;
    }

    private void measure(String name, Supplier<List<MergedResult>> before, Supplier<List<MergedResult>> after, int expected) {
        assertThat(before.get()).isEqualTo(after.get()).hasSize(expected);
        String b = percentiles(before);
        String a = percentiles(after);
        String line = "%-22s before %s | after %s".formatted(name, b, a);
        report.add(line);
        System.out.println("[perf] " + line);
    }

    private static String percentiles(Supplier<List<MergedResult>> call) {
        for (int i = 0; i < WARMUP; i++) {
            call.get();
        }
        double[] ms = new double[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long t = System.nanoTime();
            call.get();
            ms[i] = (System.nanoTime() - t) / 1e6;
        }
        Arrays.sort(ms);
        return "p50 %6.2f ms, p95 %6.2f ms, p99 %6.2f ms".formatted(at(ms, 0.50), at(ms, 0.95), at(ms, 0.99));
    }

    private static double at(double[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private List<UUID> recentIds(int limit) {
        return store.recentlyPublished(SINCE, UNTIL, Set.of(), Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED), limit);
    }

    @Test
    void homeListLatencyBeforeAndAfterBatching() {
        report.add("catalog: %d resources (%d events), %d calls after %d warm-up".formatted(RESOURCES, EVENTS, CALLS, WARMUP));
        measure("/recent size=12", () -> oneByOne(recentIds(12)), () -> recent.list(SINCE, UNTIL, Set.of(), 12), 12);
        measure("/recent size=24", () -> oneByOne(recentIds(24)), () -> recent.list(SINCE, UNTIL, Set.of(), 24), 24);
        measure("/upcoming size=6", () -> oneByOne(store.upcomingEvents(TODAY, 6)), () -> upcoming.list(TODAY, 6), 6);
        measure("/upcoming size=60", () -> oneByOne(store.upcomingEvents(TODAY, 60)), () -> upcoming.list(TODAY, 60), 60);
    }
}
