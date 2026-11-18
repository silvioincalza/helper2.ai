package ai.helper2.scout.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.platform.AuditTrail
import ai.helper2.platform.llm.ModelCallMetrics
import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentProposal
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.GapKind
import ai.helper2.scout.GapTarget
import ai.helper2.scout.RunLock
import ai.helper2.scout.ValidationResult
import ai.helper2.scout.usecase.RecheckEnrichments
import ai.helper2.scout.usecase.StoredAnswers
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PromptVersionOfStoredRunsIT {

  companion object {
    const val LABEL_OF_THE_RELEASED_PROMPT = "enrich-v12"
    const val HASH_OF_THE_RELEASED_PROMPT = "128470b5"
    const val MIGRATION = "db/migration/V2026.11.8.1__prompt_version_is_the_prompt_hash.sql"
  }

  @Autowired lateinit var jdbc: JdbcClient
  @Autowired lateinit var store: EnrichmentStore
  @Autowired lateinit var answers: StoredAnswers

  private val resource = UUID.randomUUID()

  @BeforeEach
  fun aResourceWithAReadmeAndNoSummary() {
    jdbc
      .sql(
        """
        INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
        VALUES (:id, :url, :url, 'Prompt version test', 'SOURCE_PROVIDED', 'OTHER', 'BUILD_WITH_AI', 'INDEXED')
        """
      )
      .param("id", resource)
      .param("url", "https://prompt-version.example/$resource")
      .update()
  }

  @AfterEach
  fun clean() {
    jdbc.sql("DELETE FROM enrichment_run WHERE resource_id = :id").param("id", resource).update()
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update()
  }

  private fun model(promptVersion: String) =
    object : EnrichmentModel {
      override val provider = "fake"
      override val model = "m"
      override val promptVersion = promptVersion

      override fun propose(input: EnrichmentInput): EnrichmentProposal =
        error("a stored run needs no model call")
    }

  private fun storeAJudgedRun(promptVersion: String) =
    store.save(
      resource,
      model(promptVersion),
      "fp",
      Instant.now(),
      ValidationResult(
        EnrichmentOutcome.VALIDATED,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
      ),
      EnrichmentOutcome.VALIDATED,
      null,
      null,
    )

  private fun migrate() {
    jdbc.sql(ClassPathResource(MIGRATION).getContentAsString(Charsets.UTF_8)).update()
  }

  private fun storedVersion() = answers.stored(resource).promptVersion

  private fun runsRequestedUnder(promptVersion: String): List<UUID> {
    val requested = mutableListOf<UUID>()
    val onlyThisResource =
      object : StoredAnswers by answers {
        override fun judgedAfter(after: UUID?, limit: Int) =
          if (after == null) listOf(resource) else emptyList()

        override fun requestRun(resourceId: UUID) {
          requested += resourceId
        }

        override fun closeItemsWithNothingToDecide() = 0
      }
    val withAReadme =
      object : EnrichmentStore by store {
        override fun load(resourceId: UUID) =
          EnrichmentMaterial(
            EnrichmentInput("Prompt version test", emptyList()),
            "hash",
            GapTarget(GapKind.GITHUB_README, "acme", "acme", resourceId),
            ResourceType.OTHER,
          )
      }
    val free =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
      }
    RecheckEnrichments(
        onlyThisResource,
        withAReadme,
        promptVersion,
        true,
        AuditTrail { _, _, _, _ -> },
        SimpleMeterRegistry(),
        free,
      )
      .runByAdmin("42")
    return requested
  }

  @Test
  fun `a run stored under the label of the released prompt is not run again for the new scheme`() {
    storeAJudgedRun(LABEL_OF_THE_RELEASED_PROMPT)

    migrate()

    assertEquals(HASH_OF_THE_RELEASED_PROMPT, storedVersion())
    assertEquals(emptyList(), runsRequestedUnder(HASH_OF_THE_RELEASED_PROMPT))
  }

  @Test
  fun `the same run is run again once the wording of the prompt changes`() {
    storeAJudgedRun(LABEL_OF_THE_RELEASED_PROMPT)
    migrate()
    val reworded =
      ModelCallMetrics.promptVersion(SpringAiEnrichmentModel.systemPrompt() + "\nOne more rule.")

    assertEquals(listOf(resource), runsRequestedUnder(reworded))
  }

  @Test
  fun `without the data step the released runs would all count as stale`() {
    storeAJudgedRun(LABEL_OF_THE_RELEASED_PROMPT)

    assertEquals(listOf(resource), runsRequestedUnder(HASH_OF_THE_RELEASED_PROMPT))
  }

  @Test
  fun `a run of an older prompt stays stale`() {
    storeAJudgedRun("enrich-v11")

    migrate()

    assertEquals("enrich-v11", storedVersion())
    assertEquals(listOf(resource), runsRequestedUnder(HASH_OF_THE_RELEASED_PROMPT))
  }
}
