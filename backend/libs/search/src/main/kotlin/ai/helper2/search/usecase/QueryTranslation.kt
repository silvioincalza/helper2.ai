package ai.helper2.search.usecase

import ai.helper2.search.AnalyzedQuery
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.QueryTranslator
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory

internal class QueryTranslation(
  private val translator: QueryTranslator,
  private val executor: ExecutorService,
  private val analyzer: QueryAnalyzer,
  private val timeout: Duration,
) {
  private val log = LoggerFactory.getLogger(QueryTranslation::class.java)

  fun start(text: String, language: String): Future<String?>? =
    if (translator === QueryTranslator.NONE) null
    else executor.submit<String?> { translator.toEnglish(text, language) }

  fun await(future: Future<String?>, text: String): String? {
    val raw =
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        future.cancel(true)
        log.info("Query translation timed out after $timeout")
        null
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
      } catch (e: Exception) {
        log.info("Query translation failed: {}", e.javaClass.simpleName)
        null
      } ?: return null
    if (URL_LIKE.containsMatchIn(raw)) return null
    val words = QueryTranslator.bounded(raw.lowercase().replace(NON_WORD, " "))
    return words.takeIf { it.isNotBlank() && it != analyzer.analyze(text).normalized }
  }

  fun withTranslation(analyzed: AnalyzedQuery, english: String?): AnalyzedQuery =
    if (english == null) analyzed
    else
      analyzed.copy(
        expandedTerms = (listOf(english) + analyzed.expandedTerms).distinct(),
        requiredVariants = (listOf(english) + analyzed.requiredVariants).distinct(),
        translation = english,
      )

  private companion object {
    val URL_LIKE = Regex("(?i)https?:|www\\.|://")
    val NON_WORD = Regex("[^\\p{L}\\p{N}\\s-]+")
  }
}
