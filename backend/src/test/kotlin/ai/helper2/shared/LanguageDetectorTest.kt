package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageDetectorTest {

    private val detector = LanguageDetector()

    @Test
    fun `natural sentences in several languages`() {
        assertEquals("it", detector.detect("app per tradurre documenti"))
        assertEquals("es", detector.detect("herramienta para traducir documentos"))
        assertEquals("de", detector.detect("Werkzeug zum Übersetzen von Dokumenten"))
        assertEquals("fr", detector.detect("outil pour traduire des documents"))
        assertEquals("en", detector.detect("tool to translate documents"))
        assertEquals("it", detector.detect("app per tradurre"))
        assertEquals("ru", detector.detect("генератор изображений"))
        assertEquals("fr", detector.detect("générateur d'images"))
    }

    @Test
    fun `short technical queries are English or unknown, never a guessed language`() {
        for (q in listOf("mcp postgres", "llm", "rag", "MCP server Postgres", "langchain agents", "k8s operator", "vector database",
            "tools in rust", "image generation for slides")) {
            val code = detector.detect(q)
            assertTrue(code == null || code == "en", "$q -> $code")
        }
    }

    @Test
    fun `too little text is unknown`() {
        assertNull(detector.detect(null))
        assertNull(detector.detect("ai"))
    }
}
