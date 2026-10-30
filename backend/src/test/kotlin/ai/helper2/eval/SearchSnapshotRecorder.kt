package ai.helper2.eval

import ai.helper2.catalog.Embedder
import ai.helper2.search.QueryTranslator
import ai.helper2.search.SearchSource
import ai.helper2.shared.LanguageDetector
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Records one snapshot per query of docs/eval/search-queries.json and docs/eval/semantic-gold.json: each source's
 * ranked hits, through the real adapters and the configured catalog, into backend/eval-data (git-ignored; third-party
 * data is never committed). A query that already has a snapshot is skipped, so a recording is frozen until its folder
 * is deleted. Never part of `test` or CI.
 *
 * `./gradlew evalRecord` with the repo-root .env (GitHub token, Tavily key, H2_NVIDIA_API_KEY for vector search) and
 * H2_DATABASE_URL pointing at a database that holds the catalog to measure. H2_EVAL_PAUSE (ISO duration, default
 * PT4S) is the pause between queries, for the sources' rate limits.
 */
@Tag("eval-record")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SearchSnapshotRecorder {

    @Autowired lateinit var sources: List<SearchSource>
    @Autowired lateinit var languages: LanguageDetector
    @Autowired lateinit var translator: ObjectProvider<QueryTranslator>
    @Autowired lateinit var embedder: ObjectProvider<Embedder>
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `records each query once against the live sources`() {
        val taker = SnapshotTaker(sources.sortedWith(compareBy({ it.descriptor.priority }, { it.descriptor.id })), languages,
            translator.getIfAvailable { QueryTranslator.NONE })
        val pause = Duration.parse(System.getenv("H2_EVAL_PAUSE") ?: "PT4S")
        val queries = (EvalFiles.searchQueries() + EvalFiles.semanticQueries()).filterNot { EvalFiles.snapshotFile(it.id).exists() }
        println("recording ${queries.size} queries into ${EvalFiles.snapshots}")
        queries.forEachIndexed { i, q ->
            if (i > 0) Thread.sleep(pause)
            val (snapshot, outcome) = taker.take(q.id, q.text)
            EvalFiles.writeSnapshot(snapshot.copy(semantic = nearest(q.text)))
            println("${q.id} " + outcome.statuses.joinToString(" ") { "${it.source.id}=${it.outcome}:${it.resultCount}" })
        }
    }

    /** The 50 nearest catalog vectors with their cosine distance and no cutoff: the input of the cutoff sweep. */
    private fun nearest(text: String): List<SemanticHit>? {
        val e = embedder.ifAvailable ?: return null
        val vector = e.embed(listOf(text), Embedder.Kind.QUERY).first().joinToString(",", "[", "]")
        return jdbc.sql("""
                SELECT r.canonical_url, e.embedding <=> CAST(:v AS vector) AS distance
                FROM resource_embedding e JOIN resource r ON r.id = e.resource_id
                WHERE e.model = :model AND r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
                ORDER BY e.embedding <=> CAST(:v AS vector)
                LIMIT 50
                """)
            .param("v", vector).param("model", e.model())
            .query { rs, _ -> SemanticHit(rs.getString(1), rs.getDouble(2)) }
            .list()
    }
}
