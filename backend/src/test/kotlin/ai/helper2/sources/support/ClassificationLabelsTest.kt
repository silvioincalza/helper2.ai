package ai.helper2.sources.support

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Precision and recall of the AI-relevance gate ([Classification.isAiRelated], used by GitHub, npm, Tavily and the
 * course scout) on the labelled items of docs/eval/classifier-labels.tsv. Skipped while the file has no labelled row,
 * or no `# min-precision` / `# min-recall` line: the owner sets both after the first labelling.
 */
class ClassificationLabelsTest {

    data class Labelled(val title: String, val description: String?, val labels: List<String>, val aiRelated: Boolean)

    data class Confusion(val tp: Int, val fp: Int, val fn: Int, val tn: Int) {
        val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
    }

    @Test
    fun `the AI gate meets the precision and recall stated in the labelled file`() {
        val file = File(docs(), "classifier-labels.tsv")
        val lines = file.readLines()
        val items = parse(lines)
        assumeTrue(items.isNotEmpty(), "$file has no labelled rows yet (see docs/eval/README.md).")
        val c = confusion(items)
        println("isAiRelated on ${items.size} labelled items: precision %.3f, recall %.3f, $c".format(c.precision, c.recall))
        val minPrecision = gate(lines, "min-precision")
        val minRecall = gate(lines, "min-recall")
        assumeTrue(minPrecision != null && minRecall != null, "Add '# min-precision <x>' and '# min-recall <x>' lines to $file.")
        assertTrue(c.precision >= minPrecision!!, "precision %.3f < %.3f: $c".format(c.precision, minPrecision))
        assertTrue(c.recall >= minRecall!!, "recall %.3f < %.3f: $c".format(c.recall, minRecall))
    }

    @Test
    fun `counts each gate decision against its label`() {
        val items = parse(listOf(
            HEADER,
            "github\tacme/llm-router\tRoute prompts between models\t\t1", // gate yes, label yes: tp
            "npm\tleft-pad\tPad a string\t\t0", // gate no, label no: tn
            "github\tacme/agentur\tWebsite of a travel agent\tagent\t0", // "agent" matches: fp
            "github\tacme/sd-ui\tA web UI for image synthesis\t\t1", // no listed word: fn
            "# min-precision 0.9",
        ))
        assertEquals(Confusion(tp = 1, fp = 1, fn = 1, tn = 1), confusion(items))
        assertEquals(0.5, confusion(items).precision, 1e-9)
        assertEquals(0.9, gate(listOf("# min-precision 0.9"), "min-precision")!!, 1e-9)
    }

    private fun confusion(items: List<Labelled>): Confusion {
        val decided = items.map { Classification.isAiRelated(it.title, it.description, it.labels) to it.aiRelated }
        return Confusion(decided.count { it == true to true }, decided.count { it == true to false },
            decided.count { it == false to true }, decided.count { it == false to false })
    }

    private companion object {
        const val HEADER = "source\ttitle\tdescription\tlabels\tai_related"

        fun docs(): File = File(System.getProperty("user.dir")).absoluteFile
            .let { if (File(it, "docs/eval").isDirectory) it else it.parentFile }.resolve("docs/eval")

        /** Rows with a 0/1 label; an empty label means "not judged yet" and is left out. */
        fun parse(lines: List<String>): List<Labelled> {
            val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            require(rows.firstOrNull() == HEADER) { "classifier-labels.tsv must start with: $HEADER" }
            return rows.drop(1).mapIndexedNotNull { i, row ->
                val c = row.split('\t')
                require(c.size == 5) { "classifier-labels.tsv line ${i + 2}: expected 5 tab-separated columns" }
                when (c[4].trim()) {
                    "" -> null
                    "1", "0" -> Labelled(c[1], c[2].ifBlank { null }, c[3].split(',').map(String::trim).filter(String::isNotEmpty), c[4].trim() == "1")
                    else -> throw IllegalArgumentException("classifier-labels.tsv line ${i + 2}: ai_related must be 0, 1 or empty")
                }
            }
        }

        fun gate(lines: List<String>, name: String): Double? =
            lines.firstNotNullOfOrNull { Regex("^#\\s*$name\\s+([0-9.]+)\\s*$").find(it.trim())?.groupValues?.get(1)?.toDouble() }
    }
}
