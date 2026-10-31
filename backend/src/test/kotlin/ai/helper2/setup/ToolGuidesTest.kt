package ai.helper2.setup

import java.net.URI
import java.time.Clock
import java.time.Duration
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
        override fun write(ref: ToolRef, materials: List<Material>): ProposedGuide {
            calls++
            return reply()
        }
    }

    private class MemoryCache : ToolGuideCache {
        val map = mutableMapOf<String, ToolGuideAnswer>()
        val ttls = mutableMapOf<String, Duration>()
        override fun get(key: String) = map[key]
        override fun put(key: String, answer: ToolGuideAnswer, ttl: Duration) {
            map[key] = answer
            ttls[key] = ttl
        }
    }

    private val ttls = ToolGuideTtls(answer = Duration.ofDays(7), unreadable = Duration.ofMinutes(10))

    private fun guides(model: ToolGuideModel?, cache: ToolGuideCache = MemoryCache(), materials: ToolMaterials = ToolMaterials { listOf(doc) }) =
        ToolGuides(materials, model, cache, clock, ttls)

    private fun failing(reason: ToolGuideUnavailable.Reason) = Model { throw ToolGuideUnavailable(reason) }

    private val good = ProposedGuide(listOf(ProposedSection("INSTALL", listOf(ProposedStep("Install it.", "readme", "Install with:", "npm install acme-x")))))

    @Test
    fun `answers OK once, then from the cache`() {
        val model = Model { good }
        val cache = MemoryCache()
        val guides = guides(model, cache)
        val a = guides.answer("id", ref)
        assertEquals(Duration.ofDays(7), cache.ttls.values.single())
        assertEquals(ToolGuideStatus.OK, a.status)
        assertEquals(a, guides.answer("id", ref))
        assertEquals(1, model.calls)
    }

    @Test
    fun `the kept answer holds the checked steps and the document links, not the document text`() {
        val cache = MemoryCache()
        val guides = guides(Model { good }, cache)
        guides.answer("id", ref)
        val kept = cache.map.values.single()
        assertEquals(listOf(MaterialRef("readme", MaterialKind.README, URI("https://github.com/acme/x"), clock.instant())), kept.materials)
        assertEquals("npm install acme-x", kept.sections.single().steps.single().code!!.text)
    }

    @Test
    fun `a section the guide does not have is rejected, never shown`() {
        val answer = ProposedGuide(listOf(ProposedSection("ANSWER", listOf(ProposedStep("Install it.", "readme", "Install with:", "npm install acme-x")))))
        val a = guides(Model { answer }).answer("id", ref)
        assertEquals(ToolGuideStatus.NO_STEPS, a.status)
        assertEquals(mapOf("SECTION" to 1), a.rejectedBy)
    }

    @Test
    fun `no documents, no model, budget and nothing kept are told apart`() {
        assertEquals(ToolGuideStatus.NO_MATERIAL, guides(Model { good }, MemoryCache(), { emptyList() }).answer("id", ref).status)
        val noRef = ToolRef.of("x", "PRODUCT", listOf("https://example.com"), emptyList())
        assertEquals(ToolGuideStatus.NO_MATERIAL, guides(null, MemoryCache(), { error("not called") }).answer("id", noRef).status)
        val none = guides(null, MemoryCache()).answer("id", ref)
        assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, none.status)
        val budget = guides(failing(ToolGuideUnavailable.Reason.BUDGET)).answer("id", ref)
        assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, budget.status)
        assertEquals("budget", budget.unavailable)
        val invented = ProposedGuide(listOf(ProposedSection("INSTALL", listOf(ProposedStep("Install.", "readme", "Install with pip", "pip install acme-x")))))
        val empty = guides(Model { invented }).answer("id", ref)
        assertEquals(ToolGuideStatus.NO_STEPS, empty.status)
        assertEquals(1, empty.rejected)
    }

    @Test
    fun `an unreadable model answer is kept briefly, so asking again soon costs no model call`() {
        val model = failing(ToolGuideUnavailable.Reason.UNREADABLE)
        val cache = MemoryCache()
        val guides = guides(model, cache)
        val first = guides.answer("id", ref)
        val again = guides.answer("id", ref)
        assertEquals(1, model.calls)
        assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, first.status)
        assertEquals("error", first.unavailable)
        assertEquals(first, again)
        assertEquals(Duration.ofMinutes(10), cache.ttls.values.single())
    }

    @Test
    fun `a provider error and a spent budget are not kept, they recover by themselves`() {
        for (reason in listOf(ToolGuideUnavailable.Reason.PROVIDER, ToolGuideUnavailable.Reason.BUDGET)) {
            val model = failing(reason)
            val cache = MemoryCache()
            val guides = guides(model, cache)
            guides.answer("id", ref)
            val again = guides.answer("id", ref)
            assertEquals(2, model.calls, reason.name)
            assertEquals(reason.shown, again.unavailable)
            assertEquals(emptyMap(), cache.map)
        }
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
