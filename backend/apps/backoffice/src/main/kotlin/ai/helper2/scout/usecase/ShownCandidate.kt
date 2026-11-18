package ai.helper2.scout.usecase

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

data class ShownCandidate(
  val revision: String,
  val url: String,
  val title: String,
  val type: String,
  val tags: List<String>,
  val snippet: String?,
  val foundBy: List<String>,
  val edited: List<String>,
)

fun interface ShownCandidates {
  fun shown(details: String?): ShownCandidate?
}

object CandidateRevision {
  @JvmStatic
  fun of(details: String?): String =
    HexFormat.of()
      .formatHex(
        MessageDigest.getInstance("SHA-256")
          .digest((details ?: "").toByteArray(StandardCharsets.UTF_8))
      )
}
