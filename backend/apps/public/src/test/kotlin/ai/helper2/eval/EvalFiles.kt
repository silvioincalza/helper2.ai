package ai.helper2.eval

import ai.helper2.shared.AudiencePath
import ai.helper2.shared.CanonicalUrl
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

data class EvalQuery(
  val id: String,
  val group: String,
  val text: String,
  val lang: String = "en",
  val from: String? = null,
  val path: AudiencePath? = null,
)

class Qrels(val grades: Map<String, Map<String, Int>>) {
  val isEmpty: Boolean
    get() = grades.isEmpty()

  fun of(queryId: String): Map<String, Int> = grades[queryId].orEmpty()

  fun fingerprint(): String =
    EvalFiles.sha256(
      grades.toSortedMap().entries.joinToString("\n") { (q, m) ->
        m.toSortedMap().entries.joinToString("\n") { (u, g) -> "$q\t$u\t$g" }
      }
    )

  companion object {
    fun parse(lines: List<String>): Qrels {
      val rows = lines.map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("#") }
      require(rows.firstOrNull() == "query_id\turl\tgrade") {
        "qrels must start with the header query_id<TAB>url<TAB>grade"
      }
      val grades = LinkedHashMap<String, MutableMap<String, Int>>()
      rows.drop(1).forEachIndexed { i, row ->
        val cols = row.split('\t')
        require(cols.size == 3) { "qrels line ${i + 2}: expected 3 tab-separated columns" }
        val url =
          requireNotNull(CanonicalUrl.of(cols[1])) { "qrels line ${i + 2}: not a URL: ${cols[1]}" }
            .value
        val grade =
          requireNotNull(cols[2].toIntOrNull()?.takeIf { it in 0..3 }) {
            "qrels line ${i + 2}: grade must be 0-3"
          }
        grades.getOrPut(cols[0]) { LinkedHashMap() }[url] = grade
      }
      return Qrels(grades)
    }
  }
}

object EvalFiles {
  val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  val root: File =
    File(System.getProperty("user.dir")).absoluteFile.let {
      if (File(it, "docs/eval").isDirectory) it else it.parentFile
    }
  val docs: File = File(root, "docs/eval")
  val data: File = File(root, "backend/eval-data")
  val snapshots: File = File(data, "search-snapshots")

  fun searchQueries(): List<EvalQuery> = json.readValue(File(docs, "search-queries.json"))

  fun semanticQueries(): List<EvalQuery> = json.readValue(File(docs, "semantic-gold.json"))

  fun qrels(): Qrels = Qrels.parse(File(docs, "search-qrels.tsv").readLines())

  fun secondJudge(): Qrels? =
    File(docs, "search-qrels-second.tsv").takeIf { it.isFile }?.let { Qrels.parse(it.readLines()) }

  fun snapshotFile(queryId: String) = File(snapshots, "$queryId.json")

  fun writeSnapshot(snapshot: SearchSnapshot) {
    snapshots.mkdirs()
    json.writerWithDefaultPrettyPrinter().writeValue(snapshotFile(snapshot.queryId), snapshot)
  }

  fun readSnapshots(): Map<String, SearchSnapshot> =
    snapshots
      .listFiles { f -> f.name.endsWith(".json") }
      .orEmpty()
      .sortedBy { it.name }
      .map { json.readValue<SearchSnapshot>(it) }
      .associateBy { it.queryId }

  fun write(name: String, text: String): File =
    File(data, name).also {
      data.mkdirs()
      it.writeText(text)
    }

  fun sha256(text: String): String =
    HexFormat.of()
      .formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
      .take(16)
}
