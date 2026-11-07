package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.Found;
import ai.helper2.search.AnalyzedQuery;
import ai.helper2.search.QueryAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
 * Latency of the index query with search inside awesome lists (ADR 0010), on a catalog of 25 000
 * resources: first with the list index empty (the query as before, the list arms find nothing),
 * then with 50 lists of 300 entries each (15 000 entry rows). 200 calls per query after a warm-up;
 * p50/p95/p99 and the EXPLAIN ANALYZE plan go to stdout and build/perf/awesome-list-search.txt. Run
 * with ./gradlew perfTest (not part of test).
 */
@Tag("perf")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwesomeListSearchPerfTest {

  static final int RESOURCES = 25_000;
  static final int LISTS = 50;
  static final int ENTRIES = 300;
  static final int WARMUP = 100;
  static final int CALLS = 200;
  static final String PREFIX = "https://perf.example/awesome/";
  static final List<String> QUERIES =
      List.of("vector database", "agent framework python", "mcp server postgres", "embeddings");

  /** Words the seeded descriptions, entry lines and headings draw from. */
  static final String[] WORDS = {
    "vector", "database", "agent", "framework", "python", "mcp", "server", "postgres",
    "embeddings", "retrieval", "chat", "prompt", "evaluation", "tracing", "browser", "speech",
    "vision", "dataset", "finetune", "inference", "gpu", "rust", "typescript", "notebook",
    "search", "memory", "workflow", "orchestration", "guardrails", "monitoring"
  };

  /**
   * 400 words: the real ones first, then filler, so a query word is as rare as in a real catalog (a
   * seeded set where every row shares a few words makes PostgreSQL scan instead of using GIN).
   */
  static final String[] VOCABULARY =
      java.util.stream.IntStream.range(0, 400)
          .mapToObj(i -> i < WORDS.length ? WORDS[i] : "topic" + i)
          .toArray(String[]::new);

  @Autowired JdbcClient jdbc;

  @Autowired ResourceStore store;

  final List<String> report = new ArrayList<>();

  @BeforeAll
  void seed() {
    jdbc.sql(
            """
            INSERT INTO source (id, name, mode, homepage) VALUES ('github', 'GitHub', 'INDEXED', 'https://github.com')
            ON CONFLICT DO NOTHING
            """)
        .update();
    // Two words of VOCABULARY per description: a real word is in about one row in two hundred.
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
                                  data_origin, popularity)
            SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, :prefix || g), :prefix || g,
                   :prefix || g, 'Perf tool ' || g,
                   'A tool for ' || (:words)[1 + g % 400] || ' and ' || (:words)[1 + (g / 400) % 400] || '. Number ' || g,
                   'SOURCE_PROVIDED', CASE WHEN g <= :lists THEN 'ARTICLE' ELSE 'LIBRARY' END, 'BUILD_WITH_AI',
                   'INDEXED', (abs(hashtext(g::text)) % 1000) / 1000.0
            FROM generate_series(1, :n) g
            """)
        .param("prefix", PREFIX)
        .param("words", VOCABULARY)
        .param("lists", LISTS)
        .param("n", RESOURCES)
        .update();
    jdbc.sql("ANALYZE resource").update();
  }

  /**
   * Resources 1..LISTS are the lists; each lists ENTRIES other resources with a line of three words
   * and a two-level heading path, built the way AwesomeListIndex builds them.
   */
  void seedLists() {
    jdbc.sql(
            """
            INSERT INTO awesome_list_entry (list_id, resource_id, heading_path, search_vector)
            SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, :prefix || l),
                   uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, :prefix || (:lists + 1 + (l * 397 + e * 83) % (:n - :lists))),
                   h.path,
                   setweight(to_tsvector('english', 'Entry ' || e || ' - a ' || (:words)[1 + (l + e) % 400] || ' '
                       || (:words)[1 + (l * e) % 400] || ' tool with ' || (:words)[1 + (e * 7) % 400]), 'A')
                   || setweight(to_tsvector('english', h.path), 'B')
            FROM generate_series(1, :lists) l, generate_series(1, :entries) e,
                 LATERAL (SELECT initcap((:words)[1 + e % 400]) || ' > ' || initcap((:words)[1 + (e / 20) % 400]) AS path) h
            ON CONFLICT DO NOTHING
            """)
        .param("prefix", PREFIX)
        .param("words", VOCABULARY)
        .param("lists", LISTS)
        .param("entries", ENTRIES)
        .param("n", RESOURCES)
        .update();
    jdbc.sql(
            """
            INSERT INTO awesome_list (list_id, search_vector)
            SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, :prefix || l),
                   to_tsvector('english', array_to_string((:words)[1 + l % 30 : 1 + l % 30 + 10], E'\\n'))
            FROM generate_series(1, :lists) l
            ON CONFLICT DO NOTHING
            """)
        .param("prefix", PREFIX)
        .param("words", VOCABULARY)
        .param("lists", LISTS)
        .update();
    jdbc.sql("ANALYZE awesome_list_entry").update();
    jdbc.sql("ANALYZE awesome_list").update();
  }

  @AfterAll
  void cleanAndWrite() throws IOException {
    jdbc.sql("DELETE FROM resource WHERE canonical_url LIKE :prefix || '%'")
        .param("prefix", PREFIX)
        .update();
    Path out = Path.of("build/perf/awesome-list-search.txt");
    Files.createDirectories(out.getParent());
    Files.write(out, report);
  }

  private List<Found> search(String text) {
    AnalyzedQuery q = new QueryAnalyzer().analyze(text);
    return store.searchFound(
        text,
        q.getRequiredVariants(),
        q.getExpandedTerms(),
        q.getSynonyms(),
        null,
        Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED),
        50,
        null);
  }

  private String percentiles(String text) {
    for (int i = 0; i < WARMUP; i++) {
      search(text);
    }
    double[] ms = new double[CALLS];
    for (int i = 0; i < CALLS; i++) {
      long t = System.nanoTime();
      search(text);
      ms[i] = (System.nanoTime() - t) / 1e6;
    }
    Arrays.sort(ms);
    return "p50 %6.2f ms, p95 %6.2f ms, p99 %6.2f ms"
        .formatted(at(ms, 0.50), at(ms, 0.95), at(ms, 0.99));
  }

  private static double at(double[] sorted, double q) {
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
  }

  /** The plan of the index query for [text], as the store runs it (one variant). */
  private List<String> plan(String text) {
    AnalyzedQuery q = new QueryAnalyzer().analyze(text);
    String variant = q.getRequiredVariants().isEmpty() ? text : q.getRequiredVariants().getFirst();
    String any = String.join(" OR ", ResourceStore.plainWords(text).split(" "));
    return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF) " + ResourceStore.searchSql(1))
        .param("anyq", any)
        .param("raw", text)
        .param("lang", "simple")
        .param("origins", new String[] {"INDEXED", "OBSERVED"})
        .param("similarity", ResourceStore.TITLE_SIMILARITY)
        .param("preselect", ResourceStore.PRESELECT)
        .param("limit", 50)
        .param("after", null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("v0", ResourceStore.plainWords(variant))
        .query(String.class)
        .list();
  }

  @Test
  void indexQueryLatencyWithoutAndWithTheListIndex() {
    report.add(
        "catalog: %d resources; list index: %d lists x %d entries; %d calls after %d warm-up"
            .formatted(RESOURCES, LISTS, ENTRIES, CALLS, WARMUP));
    List<String> before = new ArrayList<>();
    for (String q : QUERIES) {
      before.add(percentiles(q));
    }
    seedLists();
    long rows = jdbc.sql("SELECT count(*) FROM awesome_list_entry").query(Long.class).single();
    report.add("awesome_list_entry rows: " + rows);
    for (int i = 0; i < QUERIES.size(); i++) {
      String line =
          "%-24s empty index %s | with lists %s"
              .formatted(QUERIES.get(i), before.get(i), percentiles(QUERIES.get(i)));
      report.add(line);
      System.out.println("[perf] " + line);
    }
    List<String> plan = plan("vector database");
    report.add("EXPLAIN ANALYZE 'vector database':");
    report.addAll(plan);
    plan.forEach(l -> System.out.println("[plan] " + l));
    assertThat(String.join("\n", plan)).contains("awesome_list_entry_search_idx");
    assertThat(search("vector database")).isNotEmpty();
  }
}
