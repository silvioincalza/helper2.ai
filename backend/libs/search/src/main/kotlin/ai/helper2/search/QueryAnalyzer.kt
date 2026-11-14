package ai.helper2.search

import ai.helper2.shared.ResourceType
import java.text.Normalizer

data class AnalyzedQuery(
  val original: String,
  val normalized: String,
  val expandedTerms: List<String>,
  val useCases: Set<String>,
  val typeHints: Set<ResourceType> = emptySet(),
  val requiredVariants: List<String> = listOf(normalized),
  val synonyms: List<String> = emptyList(),
  val contentTerms: List<String> = normalized.split(' ').filter { it.isNotBlank() },
  val translation: String? = null,
) {
  val lexiconTerms: List<String>
    get() = expandedTerms.filter { it != translation }
}

class QueryAnalyzer {

  private data class Entry(
    val useCase: String,
    val english: String,
    val en: Set<String>,
    val it: Set<String>,
  )

  private val lexicon =
    listOf(
      Entry(
        "writing",
        "writing",
        setOf("write", "writing", "copywriting", "blog", "essay"),
        setOf("scrivere", "scrittura", "testi", "articoli"),
      ),
      Entry(
        "translation",
        "translation",
        setOf("translate", "translation", "translator"),
        setOf("tradurre", "traduzione", "traduttore", "traduzioni"),
      ),
      Entry(
        "summarization",
        "summarize",
        setOf("summarize", "summarise", "summary", "summarization", "tldr"),
        setOf("riassumere", "riassunto", "riassunti", "sintesi"),
      ),
      Entry(
        "image-generation",
        "image generation",
        setOf("image", "images", "illustration", "picture", "text-to-image"),
        setOf("immagine", "immagini", "illustrazione", "foto"),
      ),
      Entry(
        "coding",
        "coding",
        setOf("code", "coding", "programming", "developer"),
        setOf("codice", "programmare", "programmazione", "sviluppo"),
      ),
      Entry(
        "research",
        "research",
        setOf("research", "papers", "literature", "study"),
        setOf("ricerca", "articoli scientifici", "studio"),
      ),
      Entry(
        "transcription",
        "transcription",
        setOf("transcribe", "transcription", "speech-to-text", "subtitles"),
        setOf("trascrivere", "trascrizione", "sottotitoli"),
      ),
      Entry(
        "automation",
        "automation",
        setOf("automate", "automation", "workflow", "workflows"),
        setOf("automatizzare", "automazione", "flussi"),
      ),
      Entry(
        "design",
        "design",
        setOf("design", "logo", "ui", "mockup"),
        setOf("grafica", "progettazione", "loghi"),
      ),
      Entry(
        "learning",
        "learning",
        setOf("learn", "learning", "tutor", "education"),
        setOf("imparare", "studiare", "apprendimento", "scuola"),
      ),
      Entry(
        "data-analysis",
        "data analysis",
        setOf("analytics", "spreadsheet", "data", "analysis"),
        setOf("analisi", "dati", "foglio di calcolo"),
      ),
      Entry(
        "chat",
        "chatbot",
        setOf("chat", "chatbot", "assistant"),
        setOf("assistente", "conversazione"),
      ),
      Entry("video", "video", setOf("video", "videos", "editing"), setOf("filmati", "montaggio")),
      Entry("audio", "audio", setOf("audio", "music", "voice", "podcast"), setOf("musica", "voce")),
    )

  fun analyze(raw: String): AnalyzedQuery {
    val normalized =
      Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().trim().replace(Regex("\\s+"), " ")
    val padded = padded(normalized)
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
        w in SERVER_WORDS && (prev == "mcp" || next == "mcp") ->
          typeHints += ResourceType.MCP_SERVER
        w in TYPE_WORDS -> typeHints += TYPE_WORDS.getValue(w)
        else -> core += w
      }
    }
    val synonyms = linkedSetOf<String>()
    var variants = listOf(core.toList())
    for ((i, w) in core.withIndex()) {
      val alternatives = SYNONYMS.firstOrNull { w in it }?.minus(w) ?: continue
      synonyms += alternatives
      variants =
        variants
          .flatMap { v ->
            listOf(v) + alternatives.map { alt -> v.toMutableList().also { it[i] = alt } }
          }
          .take(MAX_VARIANTS)
    }
    val required =
      variants
        .map { it.joinToString(" ") }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(normalized) }
    return AnalyzedQuery(
      raw,
      normalized,
      expanded.toList(),
      useCases,
      typeHints,
      required,
      synonyms.toList(),
      contentTerms = contentTermsOf(tokensOf(normalized)),
    )
  }

  private fun tokensOf(normalized: String): List<String> =
    normalized
      .split(TOKEN_SEPARATOR)
      .map { it.replace(EDGE_PUNCTUATION, "") }
      .filter { it.isNotEmpty() }

  private fun contentTermsOf(words: List<String>): List<String> {
    fun isFiller(w: String) = w in FUNCTION_WORDS || w in REQUEST_WORDS
    fun isContent(w: String?) = w != null && !isFiller(w) && w !in TYPE_WORDS
    val terms = words.filterIndexed { i, w ->
      val next = words.getOrNull(i + 1)
      val prev = words.getOrNull(i - 1)
      when {
        isFiller(w) -> false
        w in SERVER_WORDS && (prev == "mcp" || next == "mcp") -> false
        w in TYPE_WORDS -> isContent(next)
        else -> true
      }
    }
    return terms.distinct().ifEmpty { words }
  }

  private companion object {
    const val MAX_VARIANTS = 4
    const val DERIVED_USE_CASE_WORDS = 2
    val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}\\-_./@:]+")
    val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")
    val SERVER_WORDS = setOf("server", "servers")
    val TYPE_WORDS =
      mapOf(
        "library" to ResourceType.LIBRARY,
        "libraries" to ResourceType.LIBRARY,
        "package" to ResourceType.LIBRARY,
        "libreria" to ResourceType.LIBRARY,
        "biblioteca" to ResourceType.LIBRARY,
        "librería" to ResourceType.LIBRARY,
        "model" to ResourceType.MODEL,
        "models" to ResourceType.MODEL,
        "modello" to ResourceType.MODEL,
        "modelo" to ResourceType.MODEL,
        "dataset" to ResourceType.DATASET,
        "datasets" to ResourceType.DATASET,
        "paper" to ResourceType.PAPER,
        "papers" to ResourceType.PAPER,
      )
    val FUNCTION_WORDS =
      setOf(
        "a",
        "an",
        "the",
        "of",
        "for",
        "to",
        "in",
        "on",
        "at",
        "by",
        "with",
        "from",
        "over",
        "into",
        "and",
        "or",
        "so",
        "that",
        "which",
        "who",
        "can",
        "could",
        "will",
        "should",
        "is",
        "are",
        "be",
        "it",
        "its",
        "my",
        "me",
        "i",
        "we",
        "you",
        "your",
        "our",
        "how",
        "what",
        "some",
        "any",
        "il",
        "lo",
        "la",
        "i",
        "gli",
        "le",
        "un",
        "uno",
        "una",
        "di",
        "da",
        "per",
        "con",
        "su",
        "che",
        "e",
        "o",
        "del",
        "della",
        "dei",
        "come",
        "mi",
        "el",
        "los",
        "las",
        "unos",
        "unas",
        "de",
        "para",
        "por",
        "en",
        "y",
        "que",
        "como",
        "les",
        "des",
        "du",
        "pour",
        "avec",
        "et",
        "der",
        "die",
        "das",
        "ein",
        "eine",
        "für",
        "mit",
        "und",
        "zu",
        "von",
      )
    val REQUEST_WORDS =
      setOf(
        "build",
        "create",
        "make",
        "find",
        "get",
        "need",
        "want",
        "use",
        "using",
        "help",
        "best",
        "good",
        "top",
        "new",
        "open",
        "free",
        "simple",
        "easy",
        "tool",
        "tools",
        "app",
        "apps",
        "application",
        "applications",
        "file",
        "files",
        "creare",
        "costruire",
        "fare",
        "trovare",
        "migliore",
        "migliori",
        "nuovo",
        "gratis",
        "crear",
        "construir",
        "hacer",
        "mejor",
        "nuevo",
        "créer",
        "erstellen",
      )
    val SYNONYMS =
      listOf(
        setOf("postgres", "postgresql"),
        setOf("js", "javascript"),
        setOf("ts", "typescript"),
        setOf("k8s", "kubernetes"),
      )
  }

  fun useCasesOf(vararg texts: String?): Set<String> {
    val text = texts.filterNotNull().joinToString(" ")
    val padded = padded(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase())
    return lexicon
      .filter { e -> (e.en + e.it).count { padded.contains(" $it ") } >= DERIVED_USE_CASE_WORDS }
      .mapTo(linkedSetOf()) { it.useCase }
  }

  private fun padded(normalized: String) =
    " ${normalized.replace(Regex("[^\\p{L}\\p{N}-]+"), " ")} "
}
