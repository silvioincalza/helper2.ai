package ai.helper2.eval

import ai.helper2.search.MergedResult
import ai.helper2.search.PopularityKind
import ai.helper2.search.Ranking
import ai.helper2.search.SourceOutcome
import ai.helper2.shared.Audience
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchReplayTest {

  private fun hit(rank: Int, url: String) =
    HitSnapshot(
      rank,
      url,
      url,
      null,
      url.substringAfterLast('/'),
      null,
      ResourceType.LIBRARY,
      Audience.UNKNOWN,
      emptySet(),
      emptyMap(),
    )

  private fun source(id: String, mode: SourceMode, priority: Int, hits: List<HitSnapshot>) =
    SourceSnapshot(id, id, mode, priority, SourceOutcome.SUCCEEDED, 100, hits)

  private fun snapshot(vararg sources: SourceSnapshot) =
    SearchSnapshot(
      "q1",
      "vector store",
      Instant.parse("2026-11-12T10:00:00Z"),
      listOf("vector", "store"),
      emptySet(),
      sources.toList(),
    )

  private fun vote(rank: Int) = 1.0 / (Ranking.FUSION_K + rank + 1)

  private fun List<MergedResult>.at(url: String) = single { it.canonicalUrl.value == url }

  private val echoed = "https://github.com/o/echo"

  private fun echoSnapshot(dataOrigin: String?) =
    snapshot(
      source(
        "github",
        SourceMode.LIVE,
        20,
        listOf(hit(0, "https://github.com/o/live"), hit(1, echoed)),
      ),
      source(
        "helper2-index",
        SourceMode.INDEXED,
        50,
        listOf(
          hit(0, "https://github.com/o/indexed"),
          hit(1, "https://github.com/o/other"),
          hit(2, echoed)
            .copy(
              description = "a vector store",
              popularity = mapOf(PopularityKind.GITHUB_STARS to 4_200L),
              dataOrigin = dataOrigin,
            ),
        ),
      ),
    )

  private fun assertEchoLosesOnlyItsVote(snapshot: SearchSnapshot) {
    val baseline = SearchReplay().rank(snapshot).at(echoed)
    val variant = SearchReplay(echoNoVote = true).rank(snapshot).at(echoed)
    assertEquals(listOf("github", "helper2-index"), variant.hits.map { it.sourceId })
    assertEquals("a vector store", variant.description)
    assertEquals(baseline.popularity, variant.popularity)
    assertEquals(baseline.sources, variant.sources)
    assertEquals(baseline.score - vote(2), variant.score, 1e-12)
  }

  @Test
  fun `an echo a live source found keeps its facts and loses only its vote`() {
    assertEchoLosesOnlyItsVote(echoSnapshot(dataOrigin = null))
  }

  @Test
  fun `an observed catalog row keeps its facts and loses only its vote`() {
    assertEchoLosesOnlyItsVote(echoSnapshot(dataOrigin = "OBSERVED"))
  }

  @Test
  fun `an unmaintained result only the index holds as observed loses its vote and keeps its penalty`() {
    val old = "https://github.com/o/old"
    val observedOnly =
      snapshot(
        source("github", SourceMode.LIVE, 20, listOf(hit(0, "https://github.com/o/live"))),
        source(
          "helper2-index",
          SourceMode.INDEXED,
          50,
          listOf(
            hit(0, old)
              .copy(updated = Instant.parse("2020-01-01T00:00:00Z"), dataOrigin = "OBSERVED")
          ),
        ),
      )

    val baseline = SearchReplay().rank(observedOnly).at(old)
    val variant = SearchReplay(echoNoVote = true).rank(observedOnly).at(old)

    assertEquals(vote(0) - Ranking().weights.unmaintained, baseline.score, 1e-12)
    assertEquals(-Ranking().weights.unmaintained, variant.score, 1e-12)
    assertEquals(listOf("helper2-index"), variant.hits.map { it.sourceId })
  }

  private val shared = "https://github.com/o/shared"
  private val onlyByMeaning = "https://github.com/o/by-meaning"

  private fun catalogSnapshot(): SearchSnapshot {
    val archived = hit(0, "https://github.com/o/archived").copy(archived = true)
    val index =
      listOf(archived) +
        (1..30).map { rank ->
          if (rank == 25) hit(rank, shared) else hit(rank, "https://github.com/o/index-$rank")
        }
    return snapshot(
      source("github", SourceMode.LIVE, 20, listOf(hit(0, "https://github.com/o/live"))),
      source("helper2-index", SourceMode.INDEXED, 50, index),
      source(
        "helper2-semantic",
        SourceMode.INDEXED,
        55,
        listOf(hit(0, shared), hit(1, onlyByMeaning)),
      ),
    )
  }

  @Test
  fun `one catalog vote admits and limits each source before it takes the best rank`() {
    val snapshot = catalogSnapshot()
    val baseline = SearchReplay().rank(snapshot)
    val variant = SearchReplay(oneCatalogVote = true).rank(snapshot)
    assertEquals(baseline.map { it.canonicalUrl }.toSet(), variant.map { it.canonicalUrl }.toSet())
    assertTrue(variant.any { it.canonicalUrl.value == onlyByMeaning })
    assertEquals(
      listOf("helper2-index", "helper2-semantic"),
      variant.at(shared).hits.map { it.sourceId },
    )
    assertEquals(baseline.at(shared).score - vote(24), variant.at(shared).score, 1e-12)
    assertEquals(baseline.at(onlyByMeaning).score, variant.at(onlyByMeaning).score, 1e-12)
  }
}
