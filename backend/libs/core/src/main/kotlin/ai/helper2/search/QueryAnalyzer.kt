package ai.helper2.search

import ai.helper2.shared.ResourceType
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
    val read = joinedTypes(padded)
    fun has(term: String) = contains(read, term)
    val useCases = linkedSetOf<String>()
    // UC-001 BR-021: a need named in another language adds its English words.
    val expanded = linkedSetOf<String>()
    for (e in lexicon) {
      val enHit = e.en.any(::has)
      val otherHit = e.other.any(::has)
      if (enHit || otherHit) useCases += e.useCase
      if (otherHit && !enHit && !has(e.english)) expanded += e.english
    }
    val words = read.trim().split(' ').filter { it.isNotEmpty() }
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

  private fun tokensOf(normalized: String): List<String> {
    val tokens =
      normalized
        .split(TOKEN_SEPARATOR)
        .map { it.replace(EDGE_PUNCTUATION, "") }
        .filter { it.isNotEmpty() }
    return joinedTypes(" ${tokens.joinToString(" ")} ").split(' ').filter { it.isNotEmpty() }
  }

  private fun contentTermsOf(words: List<String>): List<String> {
    fun isFiller(w: String) = w in FUNCTION_WORDS || w in REQUEST_WORDS || w in DEVICE_WORDS
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
    return terms.distinct().ifEmpty { words }.flatMap { TYPE_PHRASE_WORDS[it] ?: listOf(it) }
  }

  private companion object {
    const val MAX_VARIANTS = 4
    const val DERIVED_USE_CASE_WORDS = 2
    val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}\\-_./@:]+")
    val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")
    val WORDS = WordLists.load("query-words")
    val SERVER_WORDS = WORDS.words("server")
    val TYPE_WORDS: Map<String, ResourceType> =
      WORDS.keys("type.")
        .flatMap { key ->
          val type = ResourceType.valueOf(key.removePrefix("type.").uppercase())
          WORDS.words(key).map { asOneWord(it) to type }
        }
        .toMap()
    val TYPE_PHRASES: Map<String, String> =
      WORDS.keys("type.")
        .flatMap { WORDS.words(it) }
        .filter { ' ' in it }
        .sortedByDescending { it.length }
        .associate { " $it " to " ${asOneWord(it)} " }

    fun asOneWord(phrase: String) = phrase.replace(' ', '-')

    val TYPE_PHRASE_WORDS: Map<String, List<String>> =
      TYPE_PHRASES.keys.map { it.trim() }.associate { asOneWord(it) to it.split(' ') }

    fun joinedTypes(padded: String) =
      TYPE_PHRASES.entries.fold(padded) { text, (phrase, word) -> text.replace(phrase, word) }

    val FUNCTION_WORDS = WORDS.words("function")
    val REQUEST_WORDS = WORDS.words("request")
    val DEVICE_WORDS = WORDS.words("device")
    val SYNONYMS = WORDS.phrasesIn("en", "synonyms").map { it.split(' ').toSet() }
  }

  fun useCasesOf(vararg texts: String?): Set<String> {
    val text = texts.filterNotNull().joinToString(" ")
    val padded = joinedTypes(padded(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()))
    return lexicon
      .filter { e -> (e.en + e.other).count { contains(padded, it) } >= DERIVED_USE_CASE_WORDS }
      .mapTo(linkedSetOf()) { it.useCase }
  }

  private fun padded(normalized: String) =
    " ${normalized.replace(Regex("[^\\p{L}\\p{N}-]+"), " ")} "

  private fun contains(padded: String, term: String) = padded.contains(" $term ")
}
