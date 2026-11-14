package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.PageTarget
import ai.helper2.scout.RunLock
import ai.helper2.scout.ValidationResult
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecheckEnrichmentsTest {

  private val withPage = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val noPage = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val already = UUID.fromString("00000000-0000-0000-0000-000000000003")

  private val badPrice = UUID.randomUUID()
  private val goodUseCase = UUID.randomUUID()
  private val oldLicence = UUID.randomUUID()
  private val badClaim = UUID.randomUUID()
  private val goodClaim = UUID.randomUUID()

  private fun field(
    id: UUID,
    field: String,
    value: String,
    excerpt: String,
    origin: ValueOrigin = ValueOrigin.AI_EXTRACTED,
  ) = StoredFieldAnswer(id, field, value, origin, Verification.VERIFIED, listOf(excerpt))

  private inner class Answers : StoredAnswers {
    val rejected = mutableSetOf<UUID>()
    val dropped = mutableSetOf<UUID>()
    val runs = mutableListOf<UUID>()
    val done = mutableSetOf<String>()
    var failNextStored = false

    private fun answer(id: UUID, prompt: String) =
      StoredAnswer(
        id,
        "Acme Notes",
        ResourceType.PRODUCT,
        prompt,
        listOf(
            field(badPrice, "pricing", "FREE", "Start your 14-day free trial today"),
            field(goodUseCase, "useCase", "writing", "Acme Notes is a writing assistant"),
            field(oldLicence, "license", "MIT", "Licensed under something"),
          )
          .filter { it.id !in rejected }
          .map { if (it.id in rejected) it.copy(verification = Verification.REJECTED) else it },
        listOf(
            StoredClaimAnswer(
              badClaim,
              "Acme Notes is the best writing assistant.",
              listOf("Acme Notes is a writing assistant"),
            ),
            StoredClaimAnswer(
              goodClaim,
              "Acme Notes is a writing assistant.",
              listOf("Acme Notes is a writing assistant"),
            ),
          )
          .filter { it.id !in dropped },
      )

    override fun judgedAfter(after: UUID?, limit: Int): List<UUID> =
      listOf(withPage, noPage, already).filter { after == null || it > after }.take(limit)

    override fun stored(resourceId: UUID): StoredAnswer {
      check(!failNextStored) { "the database went away" }
      return answer(resourceId, if (resourceId == already) "enrich-v4" else "enrich-v3")
    }

    override fun rejectFields(ids: List<UUID>): Int = ids.count { rejected.add(it) }

    override fun dropClaims(ids: List<UUID>): Int = ids.count { dropped.add(it) }

    override fun requestRun(resourceId: UUID) {
      runs += resourceId
    }

    override fun done(name: String) = name in done

    override fun markDone(name: String) {
      done += name
    }

    var closings = 0

    override fun closeItemsOfUnstatedValues(): Int = if (closings++ == 0) 4 else 0
  }

  private val enrichment =
    object : EnrichmentStore {
      override fun load(resourceId: UUID): EnrichmentMaterial =
        EnrichmentMaterial(
          EnrichmentInput("Acme Notes", emptyList()),
          "hash",
          null,
          ResourceType.PRODUCT,
          if (resourceId == noPage) null else PageTarget(URI("https://acme.example/"), resourceId),
        )

      override fun lastFingerprint(resourceId: UUID): String? = null

      override fun resourcesTitled(titles: Collection<String>, resourceId: UUID) =
        emptyMap<String, UUID>()

      override fun save(
        resourceId: UUID,
        model: EnrichmentModel,
        inputFingerprint: String,
        startedAt: Instant,
        result: ValidationResult?,
        outcome: EnrichmentOutcome,
        failure: String?,
        extraEvidence: String?,
      ) = error("a re-check never saves a run")
    }

  private val audit = mutableListOf<String>()
  private val trail = AuditTrail { _, action, _, outcome -> audit += "$action $outcome" }
  private val meters = SimpleMeterRegistry()

  private val free =
    object : RunLock {
      override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
    }

  private val held =
    object : RunLock {
      override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
    }

  private fun recheck(
    answers: Answers,
    canEnrich: Boolean = true,
    batch: Int = 2,
    lock: RunLock = free,
  ) = RecheckEnrichments(answers, enrichment, "enrich-v4", canEnrich, trail, meters, lock, batch)

  @Test
  fun `a free trial price is rejected, a superlative claim dropped, the rest kept`() {
    val answers = Answers()
    val r = recheck(answers).runByAdmin("42")!!
    assertEquals(3, r.checked)
    assertEquals(setOf(badPrice), answers.rejected)
    assertEquals(setOf(badClaim), answers.dropped)
    assertEquals(1, r.fieldsRejected)
    assertEquals(1, r.claimsDropped)
    assertTrue(audit.single().startsWith("RECHECK_ENRICHMENT CHECKED 3"))
    assertEquals(
      1.0,
      meters.get("helper2.scout.recheck").tag("result", "claims_dropped").counter().count(),
    )
  }

  @Test
  fun `only a resource that still misses facts, has pages to read and predates the prompt runs again`() {
    val answers = Answers()
    val r = recheck(answers).runByAdmin("42")!!
    assertEquals(listOf(withPage), answers.runs)
    assertEquals(1, r.runsRequested)
  }

  @Test
  fun `without a model nothing goes back to the backlog`() {
    val answers = Answers()
    recheck(answers, canEnrich = false).runByAdmin("42")!!
    assertTrue(answers.runs.isEmpty())
  }

  @Test
  fun `a second re-check changes nothing`() {
    val answers = Answers()
    recheck(answers).runByAdmin("42")!!
    val second = recheck(answers).runByAdmin("42")!!
    assertEquals(0, second.fieldsRejected)
    assertEquals(0, second.claimsDropped)
  }

  @Test
  fun `a re-check closes the review items whose only issues were unstated values`() {
    val answers = Answers()
    val first = recheck(answers).runByAdmin("42")!!
    val second = recheck(answers).runByAdmin("42")!!
    assertEquals(4, first.itemsClosed)
    assertEquals(0, second.itemsClosed)
    assertEquals(4.0, meters.counter("helper2.scout.recheck", "result", "items_closed").count())
  }

  @Test
  fun `the first re-check runs once across restarts`() {
    val answers = Answers()
    val first = recheck(answers).runOnce()
    assertEquals(3, first?.checked)
    assertNull(recheck(answers).runOnce())
    assertEquals(setOf(RecheckEnrichments.TASK), answers.done)
  }

  @Test
  fun `while a scout run holds the lock the first re-check waits for the next start`() {
    val answers = Answers()
    assertNull(recheck(answers, lock = held).runOnce())
    assertTrue(answers.done.isEmpty())
    assertEquals(3, recheck(answers).runOnce()?.checked)
  }

  @Test
  fun `an admin re-check while a scout run holds the lock changes nothing and is not audited`() {
    val answers = Answers()
    assertNull(recheck(answers, lock = held).runByAdmin("42"))
    assertTrue(answers.rejected.isEmpty())
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `a re-check that fails half way is not marked done`() {
    val answers = Answers()
    val failing =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? {
          answers.failNextStored = true
          return task.get()
        }
      }
    assertFailsWith<IllegalStateException> { recheck(answers, lock = failing).runOnce() }
    assertTrue(answers.done.isEmpty())
  }

  @Test
  fun `a stored claim with a partial number is dropped, a separated thousand kept`() {
    val partial = UUID.randomUUID()
    val separated = UUID.randomUUID()
    val dropped = mutableListOf<UUID>()
    val answers =
      object : StoredAnswers {
        override fun judgedAfter(after: UUID?, limit: Int) =
          if (after == null) listOf(withPage) else emptyList()

        override fun stored(resourceId: UUID) =
          StoredAnswer(
            resourceId,
            "Acme",
            ResourceType.PRODUCT,
            "enrich-v4",
            emptyList(),
            listOf(
              StoredClaimAnswer(
                partial,
                "Acme supports 4 tokens.",
                listOf("Acme supports 4000 tokens."),
              ),
              StoredClaimAnswer(
                separated,
                "Acme supports 4,000 tokens.",
                listOf("Acme supports 4000 tokens."),
              ),
            ),
          )

        override fun rejectFields(ids: List<UUID>) = 0

        override fun dropClaims(ids: List<UUID>): Int {
          dropped += ids
          return ids.size
        }

        override fun requestRun(resourceId: UUID) {}

        override fun done(name: String) = false

        override fun markDone(name: String) {}
      }
    RecheckEnrichments(answers, enrichment, "enrich-v4", true, trail, meters, free)
      .runByAdmin("42")!!
    assertEquals(listOf(partial), dropped)
  }

  @Test
  fun `a news item loses the facts of what it is about, its topic stays`() {
    val price = UUID.randomUUID()
    val type = UUID.randomUUID()
    val topic = UUID.randomUUID()
    val rejected = mutableListOf<UUID>()
    val answers =
      object : StoredAnswers {
        override fun judgedAfter(after: UUID?, limit: Int) =
          if (after == null) listOf(withPage) else emptyList()

        override fun stored(resourceId: UUID) =
          StoredAnswer(
            resourceId,
            "Google Gemini Carbon promises a leap in AI coding",
            ResourceType.NEWS,
            "enrich-v3",
            listOf(
              field(price, "pricing", "FREEMIUM", "It comes with a free plan for developers"),
              field(type, "type", "MODEL", "its latest Gemini 4 Carbon model")
                .copy(verification = Verification.CONFLICTING),
              field(topic, "useCase", "coding", "key areas like cybersecurity and coding"),
            ),
            emptyList(),
          )

        override fun rejectFields(ids: List<UUID>) = ids.count { rejected.add(it) }

        override fun dropClaims(ids: List<UUID>) = 0

        override fun requestRun(resourceId: UUID) {}

        override fun done(name: String) = false

        override fun markDone(name: String) {}
      }
    val r =
      RecheckEnrichments(answers, enrichment, "enrich-v5", true, trail, meters, free)
        .runByAdmin("42")!!
    assertEquals(setOf(price, type), rejected.toSet())
    assertEquals(2, r.fieldsRejected)
  }
}
