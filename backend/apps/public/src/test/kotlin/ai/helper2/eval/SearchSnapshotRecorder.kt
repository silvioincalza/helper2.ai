package ai.helper2.eval

import ai.helper2.catalog.Embedder
import ai.helper2.search.LanguageDetector
import ai.helper2.search.QueryTranslator
import ai.helper2.search.SearchSource
import ai.helper2.shared.SourceMode
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient

@Tag("eval-record")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SearchSnapshotRecorder {

  @Autowired lateinit var sources: List<SearchSource>
  @Autowired lateinit var languages: LanguageDetector
  @Autowired lateinit var translator: ObjectProvider<QueryTranslator>
  @Autowired lateinit var embedder: ObjectProvider<Embedder>
  @Autowired lateinit var jdbc: JdbcClient

  @Test
  fun `records each query once against the live sources`() {
    val taker =
      SnapshotTaker(
        sources.sortedWith(compareBy({ it.descriptor.priority }, { it.descriptor.id })),
        languages,
        translator.getIfAvailable { QueryTranslator.NONE },
      )
    val pause = Duration.parse(System.getenv("H2_EVAL_PAUSE") ?: "PT4S")
    val only =
      System.getenv("H2_EVAL_ONLY")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
    val queries =
      (EvalFiles.searchQueries() + EvalFiles.semanticQueries())
        .filter { only == null || it.id in only }
        .filterNot { EvalFiles.snapshotFile(it.id).exists() }
    println("recording ${queries.size} queries into ${EvalFiles.snapshots}")
    queries.forEachIndexed { i, q ->
      if (i > 0) Thread.sleep(pause)
      val (snapshot, outcome) = taker.take(q.id, q.text, q.path)
      EvalFiles.writeSnapshot(withDataOrigins(snapshot).copy(semantic = nearest(q.text)))
      println(
        "${q.id} " +
          outcome.statuses.joinToString(" ") { "${it.source.id}=${it.outcome}:${it.resultCount}" }
      )
    }
  }

  private fun withDataOrigins(snapshot: SearchSnapshot): SearchSnapshot {
    val catalog = snapshot.sources.filter { it.mode != SourceMode.LIVE }
    val urls = catalog.flatMap { s -> s.hits.map { it.canonicalUrl } }.distinct()
    if (urls.isEmpty()) return snapshot
    val origins =
      jdbc
        .sql("SELECT canonical_url, data_origin FROM resource WHERE canonical_url = ANY(:urls)")
        .param("urls", urls.toTypedArray())
        .query { rs, _ -> rs.getString(1) to rs.getString(2) }
        .list()
        .toMap()
    return snapshot.copy(
      sources =
        snapshot.sources.map { s ->
          if (s.mode == SourceMode.LIVE) s
          else s.copy(hits = s.hits.map { it.copy(dataOrigin = origins[it.canonicalUrl]) })
        }
    )
  }

  private fun nearest(text: String): List<SemanticHit>? {
    val e = embedder.ifAvailable ?: return null
    val vector = e.embed(listOf(text), Embedder.Kind.QUERY).first().joinToString(",", "[", "]")
    return jdbc
      .sql(
        """
                SELECT r.canonical_url, e.embedding <=> CAST(:v AS vector) AS distance
                FROM resource_embedding e JOIN resource r ON r.id = e.resource_id
                WHERE e.model = :model AND r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
                ORDER BY e.embedding <=> CAST(:v AS vector)
                LIMIT 50
                """
      )
      .param("v", vector)
      .param("model", e.model())
      .query { rs, _ -> SemanticHit(rs.getString(1), rs.getDouble(2)) }
      .list()
  }
}
