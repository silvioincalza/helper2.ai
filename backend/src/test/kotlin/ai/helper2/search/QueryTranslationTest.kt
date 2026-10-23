package ai.helper2.search

import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.LanguageDetector
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SearchResources with a detected non-English query: stemmer language, translation, and every way it can fail. */
class QueryTranslationTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val languages = LanguageDetector()

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private val noCache = object : SearchCache {
        override fun get(key: String): CachedHits? = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    private fun run(text: String, timeout: Duration = Duration.ofSeconds(2), translator: QueryTranslator): Pair<SourceQuery, SearchOutcome> {
        var seen: SourceQuery? = null
        val src = object : SearchSource {
            override val descriptor = descriptor("catalog")
            override fun search(query: SourceQuery): List<SourceHit> = emptyList<SourceHit>().also { seen = query }
        }
        val out = SearchResources(listOf(src), noCache, { emptyMap() }, executor, Clock.systemUTC(),
            languages = languages, translator = translator, translateTimeout = timeout).search(SearchRequest(text))
        return seen!! to out
    }

    @Test
    fun `a Spanish query is searched in English first and disclosed`() {
        val (q, out) = run("herramienta para traducir documentos") { _, lang -> assertEquals("es", lang); "Document translation tool" }
        assertEquals("es", q.language)
        assertEquals("document translation tool", q.requiredVariants.first())
        assertTrue("document translation tool" in q.expandedTerms)
        assertTrue(out.notices.any { it.code == NoticeCode.QUERY_EXPANDED && "document translation tool" in it.message })
    }

    @Test
    fun `a long translation is cut to the word limit the prompt states`() {
        val long = (1..20).joinToString(" ") { "word$it" }
        val (q, _) = run("herramienta para traducir documentos") { _, _ -> long }
        assertEquals((1..QueryTranslator.MAX_WORDS).joinToString(" ") { "word$it" }, q.requiredVariants.first())
    }

    @Test
    fun `translation time is not taken from a source's own timeout or latency`() {
        val seen = AtomicReference<SourceQuery>()
        val fast = object : SearchSource {
            override val descriptor = descriptor("github", timeout = Duration.ofMillis(200))
            override fun search(query: SourceQuery): List<SourceHit> = emptyList<SourceHit>().also { seen.set(query) }
        }
        val slowModel = QueryTranslator { _, _ -> Thread.sleep(400); "document translation tool" }
        val out = SearchResources(listOf(fast), noCache, { emptyMap() }, executor, Clock.systemUTC(),
            languages = languages, translator = slowModel, translateTimeout = Duration.ofSeconds(2))
            .search(SearchRequest("herramienta para traducir documentos"))

        val status = out.statuses.single()
        assertEquals(SourceOutcome.SUCCEEDED, status.outcome)
        assertTrue(status.latency!! < Duration.ofMillis(200), "latency ${status.latency} includes the translation")
        assertEquals("document translation tool", seen.get().requiredVariants.first())
    }

    @Test
    fun `sources that never read the translation run while the model is still translating`() {
        val semanticCalled = CountDownLatch(1)
        val semantic = object : SearchSource {
            override val descriptor = descriptor("semantic", mode = ai.helper2.shared.SourceMode.INDEXED).copy(usesTranslation = false)
            override fun search(query: SourceQuery): List<SourceHit> = emptyList<SourceHit>().also { semanticCalled.countDown() }
        }
        val seenByIndex = AtomicReference<SourceQuery>()
        val index = object : SearchSource {
            override val descriptor = descriptor("index", mode = ai.helper2.shared.SourceMode.INDEXED)
            override fun search(query: SourceQuery): List<SourceHit> = emptyList<SourceHit>().also { seenByIndex.set(query) }
        }
        // The model answers only once the semantic source has been called: it must not wait for the model.
        val model = QueryTranslator { _, _ -> if (semanticCalled.await(2, TimeUnit.SECONDS)) "document translation tool" else null }
        SearchResources(listOf(semantic, index), noCache, { emptyMap() }, executor, Clock.systemUTC(),
            languages = languages, translator = model, translateTimeout = Duration.ofSeconds(3))
            .search(SearchRequest("herramienta para traducir documentos"))

        assertEquals("document translation tool", seenByIndex.get().requiredVariants.first())
    }

    @Test
    fun `English queries never call the model`() {
        val calls = AtomicInteger()
        val (q, _) = run("tool to translate documents") { _, _ -> calls.incrementAndGet(); "x" }
        assertEquals(0, calls.get())
        assertNull(q.language)
    }

    @Test
    fun `a slow model is cut off and the search runs untranslated`() {
        val (q, out) = run("herramienta para traducir documentos", Duration.ofMillis(200)) { _, _ -> Thread.sleep(5_000); "late" }
        assertEquals("es", q.language)
        assertTrue(q.requiredVariants.none { "late" in it })
        assertTrue(out.results.isEmpty())
    }

    @Test
    fun `no model or spent budget means untranslated search`() {
        val (q, _) = run("herramienta para traducir documentos", translator = QueryTranslator.NONE)
        assertEquals("es", q.language)
        assertTrue(q.expandedTerms.none { "translation" in it })
    }

    @Test
    fun `a failing model is not an error`() {
        val (q, _) = run("herramienta para traducir documentos") { _, _ -> throw IllegalStateException("provider down") }
        assertEquals("es", q.language)
    }

    @Test
    fun `model output is reduced to plain words and links are refused`() {
        val (q1, _) = run("herramienta para traducir documentos") { _, _ -> "\"Translate <b>docs</b>!\"" }
        assertEquals("translate b docs b", q1.requiredVariants.first())
        val (q2, _) = run("herramienta para traducir documentos") { _, _ -> "see https://evil.example now" }
        assertTrue(q2.requiredVariants.none { "evil" in it })
    }
}
