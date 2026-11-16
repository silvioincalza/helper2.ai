package ai.helper2.search

import ai.helper2.shared.ResourceType
import ai.helper2.shared.WordBounds
import ai.helper2.shared.WordLists
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
    val other: Set<String>,
  )

  private val lexicon =
    WORDS.phrases("usecases").map { id ->
      Entry(
        id,
        WORDS.phrasesIn("en", "usecase.$id.query").single(),
        WORDS.wordsIn("en", "usecase.$id"),
        (WordLists.LANGUAGES - "en").flatMapTo(linkedSetOf()) { WORDS.wordsIn(it, "usecase.$id") },
      )
    }

  fun analyze(raw: String): AnalyzedQuery {
    val normalized =
      Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().trim().replace(Regex("\\s+"), " ")
    val padded = padded(normalized)
    fun has(term: String) = contains(padded, term)
    val useCases = linkedSetOf<String>()
    val expanded = linkedSetOf<String>()
    for (e in lexicon) {
      val enHit = e.en.any(::has)
      val otherHit = e.other.any(::has)
      if (enHit || otherHit) useCases += e.useCase
      if (otherHit && !enHit && !has(e.english)) expanded += e.english
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
    SEPARATE(normalized)
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
    val WORDS = WordLists.load("query-words")
    val SEPARATE = WordLists.separator(WORDS.allPhrases())
    val SERVER_WORDS = WORDS.words("server")
    val TYPE_WORDS: Map<String, ResourceType> =
      WORDS.keys("type.")
        .flatMap { key ->
          val type = ResourceType.valueOf(key.removePrefix("type.").uppercase())
          WORDS.words(key).map { it to type }
        }
        .toMap()
    val FUNCTION_WORDS = WORDS.words("function")
    val REQUEST_WORDS = WORDS.words("request")
    val SYNONYMS = WORDS.phrasesIn("en", "synonyms").map { it.split(' ').toSet() }
  }

  fun useCasesOf(vararg texts: String?): Set<String> {
    val text = texts.filterNotNull().joinToString(" ")
    val padded = padded(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase())
    return lexicon
      .filter { e -> (e.en + e.other).count { contains(padded, it) } >= DERIVED_USE_CASE_WORDS }
      .mapTo(linkedSetOf()) { it.useCase }
  }

  private fun padded(normalized: String) =
    " ${SEPARATE(normalized).replace(Regex("[^\\p{L}\\p{N}-]+"), " ")} "

  private fun contains(padded: String, term: String) =
    padded.contains(" $term ") ||
      (term.any { WordBounds.isSpaceless(it.code) } && WordBounds.containsOnWords(padded, term))
}
