package ai.helper2.search

import ai.helper2.shared.ResourceType
import java.text.Normalizer

data class AnalyzedQuery(
    val original: String,
    /** Lower-cased, NFKC, single-spaced. Used for cache keys. */
    val normalized: String,
    /** English equivalents added for non-English lexicon hits (disclosed to the user). */
    val expandedTerms: List<String>,
    val useCases: Set<String>,
    /** Types the query names ("MCP server", "library"...): a ranking boost, not words that must match. */
    val typeHints: Set<ResourceType> = emptySet(),
    /**
     * The words a result should contain, as alternative spellings: type words removed, synonyms
     * substituted ("mcp postgres", "mcp postgresql"). The index scores a result higher when it
     * contains every word of at least one variant. Never empty.
     */
    val requiredVariants: List<String> = listOf(normalized),
    /** Synonyms of query words, searched as optional alternatives (disclosed like expansions). */
    val synonyms: List<String> = emptyList(),
)

/**
 * Deterministic query analysis from a fixed EN/IT lexicon. Not an LLM: the same query always
 * yields the same interpretation, and every expansion is shown to the user.
 */
class QueryAnalyzer {

    private data class Entry(val useCase: String, val english: String, val en: Set<String>, val it: Set<String>)

    private val lexicon = listOf(
        Entry("writing", "writing", setOf("write", "writing", "copywriting", "blog", "essay"), setOf("scrivere", "scrittura", "testi", "articoli")),
        Entry("translation", "translation", setOf("translate", "translation", "translator"), setOf("tradurre", "traduzione", "traduttore", "traduzioni")),
        Entry("summarization", "summarize", setOf("summarize", "summarise", "summary", "summarization", "tldr"), setOf("riassumere", "riassunto", "riassunti", "sintesi")),
        Entry("image-generation", "image generation", setOf("image", "images", "illustration", "picture", "text-to-image"), setOf("immagine", "immagini", "illustrazione", "foto")),
        Entry("coding", "coding", setOf("code", "coding", "programming", "developer"), setOf("codice", "programmare", "programmazione", "sviluppo")),
        Entry("research", "research", setOf("research", "papers", "literature", "study"), setOf("ricerca", "articoli scientifici", "studio")),
        Entry("transcription", "transcription", setOf("transcribe", "transcription", "speech-to-text", "subtitles"), setOf("trascrivere", "trascrizione", "sottotitoli")),
        Entry("automation", "automation", setOf("automate", "automation", "workflow", "workflows"), setOf("automatizzare", "automazione", "flussi")),
        Entry("design", "design", setOf("design", "logo", "ui", "mockup"), setOf("grafica", "progettazione", "loghi")),
        Entry("learning", "learning", setOf("learn", "learning", "tutor", "education"), setOf("imparare", "studiare", "apprendimento", "scuola")),
        Entry("data-analysis", "data analysis", setOf("analytics", "spreadsheet", "data", "analysis"), setOf("analisi", "dati", "foglio di calcolo")),
        Entry("chat", "chatbot", setOf("chat", "chatbot", "assistant"), setOf("assistente", "conversazione")),
        Entry("video", "video", setOf("video", "videos", "editing"), setOf("filmati", "montaggio")),
        Entry("audio", "audio", setOf("audio", "music", "voice", "podcast"), setOf("musica", "voce")),
    )

    fun analyze(raw: String): AnalyzedQuery {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().trim().replace(Regex("\\s+"), " ")
        val padded = " ${normalized.replace(Regex("[^\\p{L}\\p{N}-]+"), " ")} "
        fun has(term: String) = padded.contains(" $term ")
        val useCases = linkedSetOf<String>()
        val expanded = linkedSetOf<String>()
        for (e in lexicon) {
            val enHit = e.en.any(::has)
            val itHit = e.it.any(::has)
            if (enHit || itHit) useCases += e.useCase
            if (itHit && !enHit && !has(e.english)) expanded += e.english
        }
        val words = padded.trim().split(' ').filter { it.isNotEmpty() }
        val typeHints = linkedSetOf<ResourceType>()
        val core = mutableListOf<String>()
        for ((i, w) in words.withIndex()) {
            val prev = words.getOrNull(i - 1)
            val next = words.getOrNull(i + 1)
            when {
                // "MCP server" / "server MCP" (Italian order): "server" alone would match "SQL Server".
                w in SERVER_WORDS && (prev == "mcp" || next == "mcp") -> typeHints += ResourceType.MCP_SERVER
                w in TYPE_WORDS -> typeHints += TYPE_WORDS.getValue(w)
                else -> core += w
            }
        }
        val synonyms = linkedSetOf<String>()
        var variants = listOf(core.toList())
        for ((i, w) in core.withIndex()) {
            val alternatives = SYNONYMS.firstOrNull { w in it }?.minus(w) ?: continue
            synonyms += alternatives
            variants = variants.flatMap { v -> listOf(v) + alternatives.map { alt -> v.toMutableList().also { it[i] = alt } } }
                .take(MAX_VARIANTS)
        }
        val required = variants.map { it.joinToString(" ") }.filter { it.isNotBlank() }.ifEmpty { listOf(normalized) }
        return AnalyzedQuery(raw, normalized, expanded.toList(), useCases, typeHints, required, synonyms.toList())
    }

    private companion object {
        const val MAX_VARIANTS = 4
        val SERVER_WORDS = setOf("server", "servers")
        /** Words that name what kind of resource is wanted, not what it is about. */
        val TYPE_WORDS = mapOf(
            "library" to ResourceType.LIBRARY, "libraries" to ResourceType.LIBRARY, "package" to ResourceType.LIBRARY,
            "libreria" to ResourceType.LIBRARY,
            "model" to ResourceType.MODEL, "models" to ResourceType.MODEL, "modello" to ResourceType.MODEL,
            "dataset" to ResourceType.DATASET, "datasets" to ResourceType.DATASET,
        )
        /** Same thing, different spelling. Kept small and explicit: each group is a deterministic, visible rule. */
        val SYNONYMS = listOf(
            setOf("postgres", "postgresql"),
            setOf("js", "javascript"),
            setOf("ts", "typescript"),
            setOf("k8s", "kubernetes"),
        )
    }

    /** Assign use cases to a resource from its own text, by the same fixed lexicon. */
    fun useCasesOf(vararg texts: String?): Set<String> =
        analyze(texts.filterNotNull().joinToString(" ")).useCases
}
