package ai.helper2.eval

import ai.helper2.search.QueryTranslator
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceFailure
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotTakerTest {

    private fun source(id: String, result: () -> List<SourceHit>) = object : SearchSource {
        override val descriptor = descriptor(id)
        override fun search(query: SourceQuery) = result()
    }

    @Test
    fun `keeps each source's own ranked hits and outcome, and replays to the live order`() {
        val github = source("github") {
            listOf(hit("github", "https://github.com/o/pg-mcp", rank = 0, type = ResourceType.MCP_SERVER),
                hit("github", "https://github.com/o/other", rank = 1))
        }
        val npm = source("npm") { listOf(hit("npm", "https://www.npmjs.com/package/pg-mcp", repo = "https://github.com/o/pg-mcp")) }
        val failing = source("arxiv") { throw SourceFailure.Unavailable("down") }

        val (snapshot, outcome) = SnapshotTaker(listOf(github, npm, failing), null, QueryTranslator.NONE).take("mcp01", "mcp server postgres")

        assertEquals(listOf("github" to 2, "npm" to 1, "arxiv" to 0), snapshot.sources.map { it.id to it.hits.size })
        assertEquals(SourceOutcome.FAILED, snapshot.sources.last().outcome)
        assertEquals(setOf(ResourceType.MCP_SERVER), snapshot.typeHints)
        assertEquals("https://github.com/o/pg-mcp", snapshot.sources[1].hits.single().repositoryUrl)
        assertEquals(outcome.results.map { it.canonicalUrl }, SearchReplay().rank(snapshot).map { it.canonicalUrl })
    }
}
