package ai.helper2.eval

import ai.helper2.shared.CategoryInput
import ai.helper2.shared.CategoryMatcher
import ai.helper2.shared.ResourceType
import java.io.File

/** One resource a person labelled: what the matcher reads, and the categories it should give. */
data class LabelledResource(val input: CategoryInput, val expected: Set<String>)

/** Precision and recall of one category over the labelled set. */
data class CategoryScore(
  val id: String,
  val truePositives: Int,
  val falsePositives: Int,
  val falseNegatives: Int,
) {
  val precision: Double?
    get() =
      (truePositives + falsePositives).takeIf { it > 0 }?.let { truePositives.toDouble() / it }

  val recall: Double?
    get() =
      (truePositives + falseNegatives).takeIf { it > 0 }?.let { truePositives.toDouble() / it }
}

/**
 * The CategoryMatcher measured against labels a person gave (ADR 0053): per category, how many of
 * the categories it gives are right (precision) and how many of the right ones it finds (recall).
 * Categories are assigned by code first; a model would be considered only where these numbers say
 * code is not enough.
 */
class CategoryEvaluation(
  private val matcher: CategoryMatcher,
  private val rows: List<LabelledResource>,
) {

  fun scores(): List<CategoryScore> {
    val ids = matcher.catalog.ids()
    val given = rows.map { it to matcher.match(it.input).toSet() }
    return ids.map { id ->
      CategoryScore(
        id,
        truePositives = given.count { (row, got) -> id in got && id in row.expected },
        falsePositives = given.count { (row, got) -> id in got && id !in row.expected },
        falseNegatives = given.count { (row, got) -> id !in got && id in row.expected },
      )
    }
  }

  /** Over every category at once (micro average). */
  fun total(): CategoryScore =
    scores().fold(CategoryScore("all", 0, 0, 0)) { a, s ->
      CategoryScore(
        "all",
        a.truePositives + s.truePositives,
        a.falsePositives + s.falsePositives,
        a.falseNegatives + s.falseNegatives,
      )
    }

  fun report(): String = buildString {
    appendLine("# Category matcher evaluation")
    appendLine()
    appendLine("${rows.size} labelled resources.")
    appendLine()
    appendLine("| category | precision | recall | TP | FP | FN |")
    appendLine("|---|---|---|---|---|---|")
    (scores() + total()).forEach {
      appendLine(
        "| ${it.id} | ${fmt(it.precision)} | ${fmt(it.recall)} | ${it.truePositives} | ${it.falsePositives} | ${it.falseNegatives} |"
      )
    }
  }

  companion object {
    private fun fmt(v: Double?) = v?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "-"

    /** The rows of [file]: the header and lines starting with # are skipped. */
    fun read(file: File): List<LabelledResource> =
      file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map(::parse)

    fun parse(line: String): LabelledResource {
      val c = csv(line)
      require(c.size >= 6) { "a row needs 6 columns: $line" }
      return LabelledResource(
        CategoryInput(
          c[0],
          c[1].ifBlank { null },
          list(c[2]),
          c[3].takeIf { it.isNotBlank() }?.let(ResourceType::valueOf),
          list(c[4]),
        ),
        list(c[5]).toSet(),
      )
    }

    private fun list(cell: String) = cell.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** One CSV line: commas split cells, double quotes hold commas, "" is a quote. */
    fun csv(line: String): List<String> {
      val cells = mutableListOf<String>()
      val cell = StringBuilder()
      var quoted = false
      var i = 0
      while (i < line.length) {
        val ch = line[i]
        when {
          quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
            cell.append('"')
            i++
          }
          ch == '"' -> quoted = !quoted
          ch == ',' && !quoted -> {
            cells += cell.toString()
            cell.clear()
          }
          else -> cell.append(ch)
        }
        i++
      }
      cells += cell.toString()
      return cells
    }
  }
}
