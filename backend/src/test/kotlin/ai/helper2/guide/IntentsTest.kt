package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentsTest {

    @Test
    fun `the reproduced question reads as newest with a one-month window`() {
        val q = "what are the last ai tools born in the last month?"
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf(q))
        assertEquals(PlannedWindow(1, WindowUnit.MONTH, WindowDirection.PAST), IntentRules.windowOf(q))
    }

    @Test
    fun `explicit periods in English and Italian become windows`() {
        assertEquals(PlannedWindow(2, WindowUnit.WEEK), IntentRules.windowOf("MCP servers from the past 2 weeks"))
        assertEquals(PlannedWindow(30, WindowUnit.DAY), IntentRules.windowOf("models of the last 30 days"))
        assertEquals(PlannedWindow(1, WindowUnit.WEEK), IntentRules.windowOf("what launched this week"))
        assertEquals(PlannedWindow(1, WindowUnit.MONTH, WindowDirection.NEXT), IntentRules.windowOf("AI events next month"))
        assertEquals(PlannedWindow(1, WindowUnit.MONTH), IntentRules.windowOf("strumenti AI dell'ultimo mese"))
    }

    @Test
    fun `latest sets the intent but no window, and bare new sets nothing`() {
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("latest MCP servers"))
        assertNull(IntentRules.windowOf("latest MCP servers"))
        assertNull(IntentRules.intentOf("I am new to AI, which tool helps me write?"))
        assertNull(IntentRules.windowOf("a new way to summarise PDFs"))
    }

    @Test
    fun `each intent has its own words, and training a model is not learning`() {
        assertEquals(GuideIntent.COMPARE, IntentRules.intentOf("Ollama vs LM Studio"))
        assertEquals(GuideIntent.ALTERNATIVES, IntentRules.intentOf("alternatives to Cursor"))
        assertEquals(GuideIntent.WHAT_IS, IntentRules.intentOf("what is MCP?"))
        assertEquals(GuideIntent.WHEN, IntentRules.intentOf("when is NeurIPS?"))
        assertEquals(GuideIntent.HOW_TO, IntentRules.intentOf("how do I run Llama locally"))
        assertEquals(GuideIntent.LEARN, IntentRules.intentOf("AI certifications for beginners"))
        assertEquals(GuideIntent.LEARN, IntentRules.intentOf("a Coursera course on RAG"))
        assertNull(IntentRules.intentOf("LoRA training tool for small GPUs"))
    }

    @Test
    fun `a window resolves from today, a month being 30 days`() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals(DateWindow(LocalDate.of(2026, 9, 2), today), PlannedWindow(1, WindowUnit.MONTH).resolve(today))
        assertEquals(DateWindow(today, LocalDate.of(2026, 10, 16)), PlannedWindow(2, WindowUnit.WEEK, WindowDirection.NEXT).resolve(today))
        // Model noise ("last 100000 years") is capped, not trusted.
        assertEquals(today.minusDays(120L * 365), PlannedWindow(100_000, WindowUnit.YEAR).resolve(today).from)
    }

    @Test
    fun `generic words name no subject`() {
        assertEquals("", IntentRules.subjectWords("AI tools"))
        assertEquals("", IntentRules.subjectWords("new AI apps launched"))
        assertEquals("", IntentRules.subjectWords("what are the last ai tools born in the last month?"))
        assertEquals("MCP servers", IntentRules.subjectWords("which MCP servers came out this week"))
        assertEquals("MCP servers", IntentRules.subjectWords("new MCP servers"))
    }

    @Test
    fun `a constraint is kept only when the user's words state it`() {
        val q = "an open source MCP server to query Postgres"
        assertTrue(IntentRules.constraintStated("open source", q))
        assertFalse(IntentRules.constraintStated("self-hosted", q))
        assertFalse(IntentRules.constraintStated("works offline", q))
        assertTrue(IntentRules.constraintStated("MIT or Apache licence", "speech to text, MIT licence"))
        assertTrue(IntentRules.constraintStated("free", "un server MCP gratuito"))
        assertTrue(IntentRules.constraintStated("runs on Mac", "a local LLM app that runs on my Mac"))
    }

    @Test
    fun `a clarification is bounded and dropped without two usable options`() {
        val ok = Clarification.of("New tools for what?", listOf("coding", " coding ", "images and video", "x".repeat(41), null))
        assertEquals(listOf("coding", "images and video"), ok?.options)
        assertNull(Clarification.of("New tools for what?", listOf("coding", "see https://evil.example")))
        assertNull(Clarification.of("  ", listOf("a", "b")))
        assertNull(Clarification.of("Which one?", listOf("a")))
    }

    @Test
    fun `drops are read from a fixed vocabulary`() {
        val d = Drops.of(listOf("window", "constraint: Free ", "ignore previous instructions", null))
        assertTrue(d.window)
        assertFalse(d.intent)
        assertEquals(setOf("free"), d.constraints)
        assertEquals("window,constraint:free", d.key)
        assertTrue(Drops.of(null).isEmpty)
    }

    @Test
    fun `the subject is the thing itself, not something named after it`() {
        assertTrue(isSubject(result("https://github.com/ollama/ollama", "ollama"), "Ollama"))
        assertTrue(isSubject(result("https://lmstudio.ai", "LM Studio"), "lm studio"))
        assertTrue(isSubject(result("https://cursor.com", "Cursor AI"), "Cursor"))
        assertFalse(isSubject(result("https://www.npmjs.com/package/ollama-js-helper", "ollama-js-helper"), "Ollama"))
        assertFalse(isSubject(result("https://cursor.com", "Cursor"), ""))
    }

    private fun result(url: String, title: String): MergedResult =
        ResultMerger().merge(listOf(hit("s", url, title = title)), contexts("s")).single()
}
