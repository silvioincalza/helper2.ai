package ai.helper2.scout.usecase

import ai.helper2.catalog.enrichment.RepositoryVisits
import ai.helper2.catalog.enrichment.RepositoryVisits.Due
import ai.helper2.catalog.enrichment.RepositoryVisits.Found
import ai.helper2.catalog.enrichment.RepositoryVisits.Gone
import ai.helper2.catalog.enrichment.RepositoryVisits.Unanswered
import ai.helper2.catalog.enrichment.RepositoryVisits.Visit
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SweepRepositoriesTest {

  private val now = Instant.parse("2026-11-18T10:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val meters = SimpleMeterRegistry()

  private class Catalog(private val due: List<Due>) : RepositoryVisits {
    val recorded = mutableListOf<Triple<UUID, Visit, Instant>>()
    var askedFor = 0

    override fun due(limit: Int): List<Due> {
      askedFor = limit
      return due.take(limit)
    }

    override fun record(recordId: UUID, visit: Visit, visitedAt: Instant) {
      recorded += Triple(recordId, visit, visitedAt)
    }
  }

  private val handedToGoneLists = mutableListOf<Collection<UUID>>()

  private fun sweep(catalog: Catalog, states: RepositoryStates) =
    SweepRepositories(catalog, states, clock, 50, meters) { handedToGoneLists += it }

  private fun counted(outcome: String) =
    meters.counter("helper2.scout.repository_sweep", "outcome", outcome).count()

  @Test
  fun `every due record gets what its repository answered, at the time of the visit`() {
    val living = Due(UUID.randomUUID(), "acme/living")
    val archived = Due(UUID.randomUUID(), "acme/archived")
    val gone = Due(UUID.randomUUID(), "acme/gone")
    val catalog = Catalog(listOf(living, archived, gone))
    val answers: Map<String, Visit> =
      mapOf(
        "acme/living" to Found(false, null, null, null),
        "acme/archived" to Found(true, null, null, null),
        "acme/gone" to Gone(),
      )

    val visited = sweep(catalog) { answers }.sweepOneBatch()

    assertEquals(3, visited)
    assertEquals(50, catalog.askedFor)
    assertEquals(
      listOf<Triple<UUID, Visit, Instant>>(
        Triple(living.recordId, answers.getValue("acme/living"), now),
        Triple(archived.recordId, answers.getValue("acme/archived"), now),
        Triple(gone.recordId, answers.getValue("acme/gone"), now),
      ),
      catalog.recorded,
    )
    assertEquals(1.0, counted("found"))
    assertEquals(1.0, counted("archived"))
    assertEquals(1.0, counted("not_found"))
  }

  @Test
  fun `two records of one repository cost one question`() {
    val npm = Due(UUID.randomUUID(), "acme/tool")
    val pypi = Due(UUID.randomUUID(), "acme/tool")
    val asked = mutableListOf<Collection<String>>()

    sweep(Catalog(listOf(npm, pypi))) {
        asked += it
        mapOf("acme/tool" to Found(false, null, null, null))
      }
      .sweepOneBatch()

    assertEquals(listOf<Collection<String>>(listOf("acme/tool")), asked)
  }

  @Test
  fun `a record whose repository got no answer is still visited, so it does not block the others`() {
    val silent = Due(UUID.randomUUID(), "acme/silent")
    val catalog = Catalog(listOf(silent))

    sweep(catalog) { emptyMap() }.sweepOneBatch()

    assertEquals(
      listOf<Triple<UUID, Visit, Instant>>(Triple(silent.recordId, Unanswered(), now)),
      catalog.recorded,
    )
    assertEquals(1.0, counted("unanswered"))
  }

  // UC-030 BR-010: only a definite "not found" reaches the rule of the lists that are gone, after
  // the visit is stored.
  @Test
  fun `the records GitHub did not find are handed to the rule of gone lists`() {
    val living = Due(UUID.randomUUID(), "acme/living")
    val gone = Due(UUID.randomUUID(), "acme/awesome-gone")
    val silent = Due(UUID.randomUUID(), "acme/silent")
    val catalog = Catalog(listOf(living, gone, silent))
    val answers: Map<String, Visit> =
      mapOf("acme/living" to Found(false, null, null, null), "acme/awesome-gone" to Gone())

    sweep(catalog) { answers }.sweepOneBatch()

    assertEquals(listOf<Collection<UUID>>(listOf(gone.recordId)), handedToGoneLists)
    assertEquals(3, catalog.recorded.size)
  }

  // UC-030 BR-010: a read that fails for another reason removes nothing.
  @Test
  fun `a batch where every repository is found or silent hands nothing to the rule of gone lists`() {
    val catalog =
      Catalog(listOf(Due(UUID.randomUUID(), "acme/living"), Due(UUID.randomUUID(), "acme/silent")))

    sweep(catalog) { mapOf("acme/living" to Found(false, null, null, null)) }.sweepOneBatch()

    assertEquals(emptyList<Collection<UUID>>(), handedToGoneLists)
  }

  @Test
  fun `nothing due asks nothing`() {
    val visited = sweep(Catalog(emptyList())) { error("not asked") }.sweepOneBatch()

    assertEquals(0, visited)
  }

  @Test
  fun `a failed question records nothing, so the same records are due again`() {
    val catalog = Catalog(listOf(Due(UUID.randomUUID(), "acme/tool")))

    assertFailsWith<IllegalStateException> {
      sweep(catalog) { throw IllegalStateException("rate limited") }.sweepOneBatch()
    }

    assertEquals(emptyList<Triple<UUID, Visit, Instant>>(), catalog.recorded)
    assertEquals(emptyList<Collection<UUID>>(), handedToGoneLists)
  }
}
