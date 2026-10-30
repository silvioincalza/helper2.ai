package ai.helper2.eval

import ai.helper2.search.PopularityKind
import ai.helper2.search.SourceOutcome
import ai.helper2.shared.Audience
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.readValue

class SearchEvaluationTest {

    private fun hit(rank: Int, url: String, repo: String? = null) = HitSnapshot(rank, url, url, repo, url.substringAfterLast('/'),
        null, ResourceType.LIBRARY, Audience.UNKNOWN, emptySet(), emptyMap())

    private fun source(id: String, vararg hits: HitSnapshot) =
        SourceSnapshot(id, id, SourceMode.LIVE, 100, SourceOutcome.SUCCEEDED, 100, hits.toList())

    // github: a (rank 0), b (rank 1); npm: c (rank 0) whose repository is b, so b and c merge into one result.
    private val snapshot = SearchSnapshot("q1", "vector store", Instant.parse("2026-10-30T10:00:00Z"), listOf("vector", "store"),
        emptySet(), listOf(
            source("github", hit(0, "https://github.com/o/a"), hit(1, "https://github.com/o/b")),
            source("npm", hit(0, "https://www.npmjs.com/package/c", repo = "https://github.com/o/b")),
        ))
    private val queries = listOf(EvalQuery("q1", "library", "vector store"), EvalQuery("q2", "library", "not recorded"))

    private fun evaluation(qrels: String) = SearchEvaluation(queries, mapOf("q1" to snapshot),
        Qrels.parse(qrels.trimIndent().lines()))

    @Test
    fun `replay merges a package with its repository and ranks the result found by two sources first`() {
        val ranked = SearchReplay().rank(snapshot)
        assertEquals(listOf("https://github.com/o/b", "https://github.com/o/a"), ranked.map { it.canonicalUrl.value })
        assertEquals(ranked, SearchReplay().rank(snapshot), "same snapshot, same order")
    }

    @Test
    fun `leaving a source out replays the others only`() {
        val ranked = SearchReplay().rank(snapshot, without = setOf("github"))
        assertEquals(listOf("npm"), ranked.flatMap { r -> r.hits.map { it.sourceId } })
    }

    @Test
    fun `a judgement on any url of a merged result grades it`() {
        val e = evaluation("""
            query_id	url	grade
            q1	https://npmjs.com/package/c	3
            q1	https://github.com/o/a	0
        """)
        val score = e.score("q1")
        // ranked [b+c (3), a (0)]: DCG = 7, ideal [3,0] = 7
        assertEquals(1.0, score.ndcg10!!, 1e-9)
        assertEquals(1.0, score.mrr!!, 1e-9)
        assertEquals(1.0, score.recall20!!, 1e-9)
        assertEquals(0.0, score.unjudged10, 1e-9)
    }

    @Test
    fun `ablation measures what the left-out source alone brought`() {
        val e = evaluation("""
            query_id	url	grade
            q1	https://github.com/o/a	2
            q1	https://github.com/o/b	2
        """)
        // without github only c (= repository b) is left: 1 of 2 relevant found
        assertEquals(0.5, e.score("q1", setOf("github")).recall20!!, 1e-9)
        assertTrue(e.report().contains("| github |"))
    }

    @Test
    fun `an unjudged query has no relevance metrics, only duplicates and unjudged share`() {
        val score = evaluation("query_id\turl\tgrade").score("q1")
        assertNull(score.ndcg10)
        assertNull(score.mrr)
        assertEquals(1.0, score.unjudged10, 1e-9)
    }

    @Test
    fun `the judging pool lists every unjudged result of the baseline and the ablations once`() {
        val pool = evaluation("""
            query_id	url	grade
            q1	https://github.com/o/a	1
        """).pool().lines().filter { it.isNotBlank() }
        assertEquals("query_id\turl\tgrade\tquery\ttitle\tsources", pool.first())
        // b and c are one resource (c's repository is b): listed once, under its identity, also when an ablation splits them
        assertEquals(listOf("https://github.com/o/b"), pool.drop(1).map { it.split('\t')[1] })
    }

    @Test
    fun `the judging pool adds the nearest catalog vectors beyond the cutoff`() {
        val withVectors = snapshot.copy(semantic = listOf(SemanticHit("https://github.com/o/a", 0.2), SemanticHit("https://x.org/far", 0.9)))
        val pool = SearchEvaluation(queries, mapOf("q1" to withVectors), Qrels.parse(listOf("query_id\turl\tgrade"))).pool()
        val urls = pool.lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t')[1] }
        assertEquals(listOf("https://github.com/o/b", "https://github.com/o/a", "https://x.org/far"), urls)
    }

    @Test
    fun `the TREC run file has one line per ranked result`() {
        val run = evaluation("query_id\turl\tgrade").trecRun("baseline").lines().filter { it.isNotBlank() }
        assertEquals(2, run.size)
        assertTrue(run.first().startsWith("q1 Q0 https://github.com/o/b 1 "))
        assertTrue(run.first().endsWith(" baseline"))
    }

    @Test
    fun `a snapshot reads back exactly as it was written`() {
        val full = snapshot.copy(typeHints = setOf(ResourceType.MCP_SERVER), semantic = listOf(SemanticHit("https://github.com/o/a", 0.31)),
            sources = snapshot.sources.map { s -> s.copy(hits = s.hits.map { it.copy(popularity = mapOf(PopularityKind.GITHUB_STARS to 42L)) }) })
        val text = EvalFiles.json.writeValueAsString(full)
        assertEquals(full, EvalFiles.json.readValue<SearchSnapshot>(text))
    }

    @Test
    fun `qrels reject a grade outside 0 to 3 and a missing header`() {
        assertFailsWith<IllegalArgumentException> { Qrels.parse(listOf("query_id\turl\tgrade", "q1\thttps://x.org/a\t4")) }
        assertFailsWith<IllegalArgumentException> { Qrels.parse(listOf("q1\thttps://x.org/a\t1")) }
        assertTrue(Qrels.parse(listOf("# comment", "query_id\turl\tgrade")).isEmpty)
    }
}
