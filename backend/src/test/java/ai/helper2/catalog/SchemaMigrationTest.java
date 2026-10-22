package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM resource WHERE data_origin = 'DEMO'").update();
    }

    private UUID insert(String url, String title, String description, String language) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description, description_origin, language,
                                      type, audience, data_origin)
                VALUES (:id, :url, :url, :title, :description, 'DEMO', :language, 'PRODUCT', 'USE_AI', 'DEMO')
                """)
            .param("id", id).param("url", url).param("title", title)
            .param("description", description).param("language", language)
            .update();
        return id;
    }

    /** Same shape as ResourceStore.search: English + simple + the query language's stemmer (null = none). */
    private long matches(String query, String language) {
        return jdbc.sql("""
                SELECT count(*) FROM resource
                WHERE data_origin = 'DEMO'
                  AND search_vector @@ (websearch_to_tsquery('english', h2_unaccent(:q))
                                     || websearch_to_tsquery('simple', h2_unaccent(:q))
                                     || websearch_to_tsquery(h2_ts_config(:lang), h2_unaccent(:q)))
                """).param("q", query).param("lang", language == null ? "simple" : language)
            .query(Long.class).single();
    }

    private long matches(String query) {
        return matches(query, null);
    }

    @Test
    void eachRowIsStemmedInItsOwnLanguage() {
        insert("https://demo.example/de", "Schneller Übersetzer", "Übersetzt Dokumente und erzeugt Bilder für Präsentationen", "de");
        insert("https://demo.example/es", "Traductor Rápido", "Traduce documentos y genera imágenes para presentaciones", "es");
        // Inflected forms reach the row through the query language's stemmer...
        // (forms the English stemmer does not join: Bildern/Bilder, traducir/traduce).
        assertThat(matches("Bildern", "de")).isEqualTo(1);
        assertThat(matches("traducir", "es")).isEqualTo(1);
        // ...and not without it.
        assertThat(matches("Bildern", null)).isZero();
        assertThat(matches("traducir", null)).isZero();
        // Exact words match in any language ('simple').
        assertThat(matches("imágenes", null)).isEqualTo(1);
    }

    @Test
    void englishRowsKeepEnglishStemmingWhateverTheQueryLanguage() {
        insert("https://demo.example/en2", "Fast Translator", "Translates documents quickly", "en");
        assertThat(matches("translating document", "de")).isEqualTo(1);
    }

    private UUID record(UUID resourceId, String extra) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                           type, retrieved_at, fingerprint, extra)
                VALUES (:id, 'curated', :id::text, :rid, 'https://demo.example/r', 'https://demo.example/r', 'r',
                        'PRODUCT', now(), 'fp', CAST(:extra AS jsonb))
                """).param("id", id).param("rid", resourceId).param("extra", extra).update();
        return id;
    }

    private double popularity(UUID resourceId) {
        return jdbc.sql("SELECT popularity FROM resource WHERE id = :id").param("id", resourceId)
            .query(Double.class).single();
    }

    @Test
    void popularityFollowsTheSourceRecordsOfAResource() {
        UUID a = insert("https://demo.example/pop-a", "A", null, "en");
        UUID b = insert("https://demo.example/pop-b", "B", null, "en");
        assertThat(popularity(a)).isZero();

        // 50k stars is the GITHUB_STARS ceiling: log-scaled to exactly 1.
        UUID rec = record(a, "{\"popularity\": [{\"kind\": \"GITHUB_STARS\", \"value\": 50000}]}");
        assertThat(popularity(a)).isEqualTo(1.0);

        // A record moving to another resource updates both.
        jdbc.sql("UPDATE source_record SET resource_id = :b WHERE id = :id").param("b", b).param("id", rec).update();
        assertThat(popularity(a)).isZero();
        assertThat(popularity(b)).isEqualTo(1.0);

        jdbc.sql("DELETE FROM source_record WHERE id = :id").param("id", rec).update();
        assertThat(popularity(b)).isZero();
    }

    @Test
    void popularityChangesIgnoreTheAsOfStampOfEachFetch() {
        String first = "{\"popularity\": [{\"kind\": \"GITHUB_STARS\", \"value\": 10, \"asOf\": \"2026-10-01T00:00:00Z\"}]}";
        String refetched = "{\"popularity\": [{\"kind\": \"GITHUB_STARS\", \"value\": 10, \"asOf\": \"2026-10-27T00:00:00Z\"}]}";
        String grown = "{\"popularity\": [{\"kind\": \"GITHUB_STARS\", \"value\": 11, \"asOf\": \"2026-10-27T00:00:00Z\"}]}";
        assertThat(samePopularity(first, refetched)).isTrue();
        assertThat(samePopularity(first, grown)).isFalse();
    }

    private boolean samePopularity(String a, String b) {
        return jdbc.sql("SELECT h2_popularity_values(CAST(:a AS jsonb)) = h2_popularity_values(CAST(:b AS jsonb))")
            .param("a", a).param("b", b).query(Boolean.class).single();
    }

    @Test
    void unknownLanguageCodeFallsBackToSimple() {
        assertThat(jdbc.sql("SELECT h2_ts_config('xx')::text").query(String.class).single()).isEqualTo("simple");
    }

    @Test
    void englishStemmingAndAccentInsensitivity() {
        insert("https://demo.example/en", "Résumé Writer", "Summarizes research papers quickly", "en");
        assertThat(matches("summarize paper")).isEqualTo(1);
        assertThat(matches("resume")).isEqualTo(1);
    }

    @Test
    void canonicalUrlIsUnique() {
        insert("https://demo.example/dup", "A", null, "en");
        assertThatThrownBy(() -> insert("https://demo.example/dup", "B", null, "en"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownPublicationDateCannotClaimAPrecision() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                      published_precision)
                VALUES (gen_random_uuid(), 'https://demo.example/bad', 'https://demo.example/bad', 'x', 'DEMO',
                        'PRODUCT', 'USE_AI', 'DEMO', 'DAY')
                """).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
