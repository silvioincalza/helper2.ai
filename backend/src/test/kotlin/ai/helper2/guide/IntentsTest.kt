package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `latest sets the intent but no window, and new sets it only right before a kind of resource`() {
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("latest MCP servers"))
        assertNull(IntentRules.windowOf("latest MCP servers"))
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("new mcp servers for postgres"))
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("any new open-source models?"))
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("nuovi modelli open source"))
        assertNull(IntentRules.windowOf("new mcp servers for postgres"))
        // A question shape at the start wins over "new" later on; "latest" still wins, as before.
        assertEquals(GuideIntent.HOW_TO, IntentRules.intentOf("how to set up new MCP servers"))
        assertEquals(GuideIntent.WHAT_IS, IntentRules.intentOf("what is the new agents SDK"))
        assertEquals(GuideIntent.NEWEST, IntentRules.intentOf("how to find the latest MCP servers"))
        assertNull(IntentRules.intentOf("I am new to AI, which tool helps me write?"))
        assertNull(IntentRules.intentOf("I'm new to MCP, which server should I start with?"))
        assertNull(IntentRules.intentOf("a new project with MCP servers"))
        assertNull(IntentRules.intentOf("sono nuovo con gli agenti"))
        assertNull(IntentRules.intentOf("a new way to summarise PDFs"))
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

    @Test
    fun `a name whose own entries are of two kinds is ambiguous, with one option per kind`() {
        val results = listOf(
            result("https://ai.google.dev/gemini", "Gemini", ResourceType.MODEL),
            result("https://github.com/google-gemini/gemini-cli", "google-gemini/gemini-cli", ResourceType.LIBRARY),
            result("https://www.npmjs.com/package/gemini", "gemini", ResourceType.LIBRARY),
            result("https://example.com/gemini-news", "Gemini", ResourceType.NEWS),
        )
        val c = assertNotNull(AmbiguousSubject.clarification("Gemini", results, planned = null))
        assertEquals("Which Gemini do you mean?", c.question)
        assertEquals(listOf("Gemini (model)", "gemini cli (tool)"), c.options)
    }

    @Test
    fun `the planner's own question back wins over the one built from the entries`() {
        val results = listOf(
            result("https://ai.google.dev/gemini", "Gemini", ResourceType.MODEL),
            result("https://github.com/google-gemini/gemini-cli", "gemini-cli", ResourceType.PRODUCT),
        )
        val planned = Clarification("Il modello o la CLI?", listOf("il modello", "la CLI"))
        assertEquals(planned, AmbiguousSubject.clarification("Gemini", results, planned))
    }

    @Test
    fun `entries of one kind, namesakes of other types and things named after the subject are not ambiguous`() {
        val oneKind = listOf(
            result("https://github.com/ollama/ollama", "ollama", ResourceType.PRODUCT),
            result("https://www.npmjs.com/package/ollama", "ollama", ResourceType.LIBRARY),
        )
        assertNull(AmbiguousSubject.clarification("Ollama", oneKind, planned = null))
        val notTheThing = listOf(
            result("https://github.com/ollama/ollama", "ollama", ResourceType.PRODUCT),
            result("https://huggingface.co/acme/ollama-finetune", "acme/ollama-finetune", ResourceType.MODEL),
            result("https://example.com/ollama-meetup", "Ollama", ResourceType.EVENT),
            result("https://example.com/ollama-course", "Ollama", ResourceType.COURSE),
        )
        assertNull(AmbiguousSubject.clarification("Ollama", notTheThing, planned = null))
    }

    @Test
    fun `an option too long for a chip names the subject and its kind`() {
        val results = listOf(
            result("https://ai.google.dev/gemini", "Gemini", ResourceType.MODEL),
            result("https://example.com/gemini-cli", "gemini command line interface for developers at scale cli", ResourceType.PRODUCT),
        )
        assertEquals(listOf("Gemini (model)", "Gemini (tool)"), AmbiguousSubject.clarification("Gemini", results, null)?.options)
    }

    @Test
    fun `a question names an event by its kind, in English and Italian`() {
        assertTrue(IntentRules.namesEvent("when is the next AI hackathon?"))
        assertTrue(IntentRules.namesEvent("NeurIPS conference"))
        assertTrue(IntentRules.namesEvent("when does the fast.ai course start?"))
        assertTrue(IntentRules.namesEvent("quando è la prossima conferenza sull'AI?"))
        assertFalse(IntentRules.namesEvent("when was Llama 4 released?"))
        assertFalse(IntentRules.namesEvent("Llama 4 release"))
        assertFalse(IntentRules.namesEvent("when did Ollama start?"))
        assertFalse(IntentRules.namesEvent("quando è iniziato Ollama?"))
        assertTrue(IntentRules.namesEvent("quando inizia il corso di fast.ai?"))
    }

    private fun result(url: String, title: String, type: ResourceType = ResourceType.LIBRARY): MergedResult =
        ResultMerger().merge(listOf(hit("s", url, title = title, type = type)), contexts("s")).single()
}
