package ai.helper2.setup

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolGuidesTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
    private val doc = Material("readme", MaterialKind.README, URI("https://github.com/acme/x"), clock.instant(),
        "Install with:\n\n```bash\nnpm install acme-x\n```\n")
    private val ref = ToolRef.of("acme-x", "LIBRARY", listOf("https://github.com/acme/x"), emptyList())

    private class Model(val reply: () -> ProposedGuide) : ToolGuideModel {
        var calls = 0
        override val model = "fake"
        override val configFingerprint = "fp"
        override fun write(ref: ToolRef, materials: List<Material>, question: String?): ProposedGuide {
            calls++
            return reply()
        }
    }

    private class MemoryCache : ToolGuideCache {
        val map = mutableMapOf<String, ToolGuideAnswer>()
        override fun get(key: String) = map[key]
        override fun put(key: String, answer: ToolGuideAnswer) {
            map[key] = answer
        }
    }

    private val good = ProposedGuide(listOf(ProposedSection("INSTALL", listOf(ProposedStep("Install it.", "readme", "Install with:", "npm install acme-x")))))

    @Test
    fun `answers OK once, then from the cache, a question is its own entry`() {
        val model = Model { good }
        val guides = ToolGuides({ listOf(doc) }, model, MemoryCache(), clock)
        val a = guides.answer("id", ref, null)
        assertEquals(ToolGuideStatus.OK, a.status)
        guides.answer("id", ref, null)
        assertEquals(1, model.calls)
        guides.answer("id", ref, "How do I  install it?")
        guides.answer("id", ref, "how do i install it?")
        assertEquals(2, model.calls)
    }

    @Test
    fun `the kept answer holds neither the question nor the document text, a hit still answers with the question`() {
        val cache = MemoryCache()
        val guides = ToolGuides({ listOf(doc) }, Model { good }, cache, clock)
        val asked = guides.answer("id", ref, "How do I install it?")
        assertEquals("How do I install it?", asked.question)
        val kept = cache.map.values.single()
        assertNull(kept.question)
        assertEquals(listOf(MaterialRef("readme", MaterialKind.README, URI("https://github.com/acme/x"), clock.instant())), kept.materials)
        assertEquals("how do I install it?", guides.answer("id", ref, "how do I install it?").question)
        assertEquals(1, cache.map.size)
    }

    @Test
    fun `no documents, no model, budget and nothing kept are told apart`() {
        assertEquals(ToolGuideStatus.NO_MATERIAL, ToolGuides({ emptyList() }, Model { good }, ToolGuideCache.NONE, clock).answer("id", ref, null).status)
        val noRef = ToolRef.of("x", "PRODUCT", listOf("https://example.com"), emptyList())
        assertEquals(ToolGuideStatus.NO_MATERIAL, ToolGuides({ error("not called") }, null, ToolGuideCache.NONE, clock).answer("id", noRef, null).status)
        val none = ToolGuides({ listOf(doc) }, null, ToolGuideCache.NONE, clock).answer("id", ref, null)
        assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, none.status)
        val budget = ToolGuides({ listOf(doc) }, Model { throw ToolGuideUnavailable(ToolGuideUnavailable.Reason.BUDGET) }, MemoryCache(), clock).answer("id", ref, null)
        assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, budget.status)
        assertEquals("budget", budget.unavailable)
        val invented = ProposedGuide(listOf(ProposedSection("INSTALL", listOf(ProposedStep("Install.", "readme", "Install with pip", "pip install acme-x")))))
        val empty = ToolGuides({ listOf(doc) }, Model { invented }, MemoryCache(), clock).answer("id", ref, null)
        assertEquals(ToolGuideStatus.NO_STEPS, empty.status)
        assertEquals(1, empty.rejected)
    }

    @Test
    fun `tool ref keeps identifiers only, from known hosts`() {
        val r = ToolRef.of("x", "MCP_SERVER", listOf("https://evil.example/a/b", "https://github.com/acme/pg-mcp.git", "https://huggingface.co/spaces/a/b"),
            listOf("@acme/pg-mcp", "../etc/passwd"))
        assertEquals("acme/pg-mcp", r.githubRepo)
        assertEquals("@acme/pg-mcp", r.npmPackage)
        assertEquals("a/b", r.hfSpace)
        assertNull(r.hfModel)
        assertEquals("org/model", ToolRef.of("m", "MODEL", listOf("https://huggingface.co/org/model"), emptyList()).hfModel)
        assertNull(ToolRef.of("t", "LIBRARY", listOf("https://github.com/topics/mcp"), emptyList()).githubRepo)
    }

    @Test
    fun `dot segments and names outside the GitHub and Hugging Face rules never become a document path`() {
        fun repo(url: String) = ToolRef.of("t", "LIBRARY", listOf(url), emptyList()).githubRepo
        fun hf(url: String) = ToolRef.of("t", "MODEL", listOf(url), emptyList())
        assertNull(repo("https://github.com/../orgs"))
        assertNull(repo("https://github.com/acme/.."))
        assertNull(repo("https://github.com/acme/."))
        assertNull(repo("https://github.com/-acme/x"))
        assertNull(repo("https://github.com/ac--me/x"))
        assertNull(repo("https://github.com/acme_co/x"))
        assertNull(repo("https://github.com/" + "a".repeat(40) + "/x"))
        assertEquals("acme-co/x.y_z", repo("https://github.com/acme-co/x.y_z"))
        assertEquals("acme/.github", repo("https://github.com/acme/.github"))
        assertNull(hf("https://huggingface.co/../model").hfModel)
        assertNull(hf("https://huggingface.co/org/..").hfModel)
        assertNull(hf("https://huggingface.co/spaces/org/..").hfSpace)
        assertNull(hf("https://huggingface.co/org/-model").hfModel)
        assertNull(hf("https://huggingface.co/org/a..b").hfModel)
        assertEquals("org/Model-7B.v1", hf("https://huggingface.co/org/Model-7B.v1").hfModel)
    }
}
