package ai.helper2.scout.usecase

import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceValidator
import ai.helper2.scout.ExtraEvidence
import ai.helper2.scout.GapBudget
import ai.helper2.scout.GapTarget
import ai.helper2.scout.LlmUnavailable
import ai.helper2.scout.Material
import ai.helper2.scout.ReadmeText
import ai.helper2.scout.ValidationResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.util.*
import org.slf4j.LoggerFactory

/**
 * Runs the model, validates deterministically, persists, and routes problems to review. A resource
 * whose enrichment fingerprint (source text, prompt version, model) equals that of its last judged
 * run is not sent again (UNCHANGED): a new star count or version must not cost a model call, and a
 * REJECTED answer is not asked for twice. A new prompt version or model changes the fingerprint, so
 * the resource is enriched again.
 *
 * Gap round (ADR 0043): when the answer is PARTIAL or REJECTED and the resource names its own
 * repository or model, the README or model card is read once ([extraEvidence]) and the model runs
 * again on the source text plus that text. The run that keeps more is saved, once, under the
 * fingerprint of the source text alone: the README is not stored, so the next run with unchanged
 * source text is UNCHANGED, and the round runs once per fingerprint.
 */
class EnrichResource
@JvmOverloads
constructor(
  private val model: EnrichmentModel,
  private val store: EnrichmentStore,
  private val clock: Clock,
  private val validator: EvidenceValidator = EvidenceValidator(),
  private val meters: MeterRegistry = SimpleMeterRegistry(),
  /** null: no gap round (no source adapters, e.g. the demo profile). */
  private val extraEvidence: ExtraEvidence? = null,
  private val gapBudget: GapBudget = GapBudget { false },
) {
  private val log = LoggerFactory.getLogger(EnrichResource::class.java)

  private companion object {
    /**
     * Relationship targets looked up per run; the validator only keeps a few relationships anyway.
     */
    const val MAX_TARGETS = 20

    val GAP_OUTCOMES = setOf(EnrichmentOutcome.PARTIAL, EnrichmentOutcome.REJECTED)
  }

  /**
   * Class and message only (never the model's answer or the source material), capped for the log
   * and the database.
   */
  private fun reason(e: RuntimeException): String =
    "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(300)

  /** false when no model is configured: backlog work would only record LLM_UNAVAILABLE again. */
  fun canEnrich(): Boolean = model.available

  fun enrich(resourceId: UUID): EnrichmentOutcome =
    attempt(resourceId).also {
      meters.counter("helper2.scout.enrichment", "outcome", it.name).increment()
    }

  private fun attempt(resourceId: UUID): EnrichmentOutcome {
    val started = clock.instant()
    val material = store.load(resourceId) ?: return EnrichmentOutcome.REJECTED
    val fingerprint = material.fingerprintFor(model)
    if (store.lastFingerprint(resourceId) == fingerprint) return EnrichmentOutcome.UNCHANGED
    var result: ValidationResult
    try {
      result = run(resourceId, material.input)
    } catch (e: LlmUnavailable) {
      log.warn("enrichment of $resourceId: LLM unavailable (${reason(e)})")
      store.save(
        resourceId,
        model,
        fingerprint,
        started,
        null,
        EnrichmentOutcome.LLM_UNAVAILABLE,
        reason(e),
        null,
      )
      return EnrichmentOutcome.LLM_UNAVAILABLE
    } catch (e: RuntimeException) {
      log.warn("enrichment of $resourceId failed (${reason(e)})")
      store.save(
        resourceId,
        model,
        fingerprint,
        started,
        null,
        EnrichmentOutcome.FAILED,
        reason(e),
        null,
      )
      return EnrichmentOutcome.FAILED
    }
    var used: String? = null
    gapRound(resourceId, material, result)?.let { (better, target) ->
      result = better
      used = target.label
    }
    store.save(resourceId, model, fingerprint, started, result, result.outcome, null, used)
    return result.outcome
  }

  private fun run(resourceId: UUID, input: EnrichmentInput): ValidationResult {
    val proposal = model.propose(input)
    val targets =
      proposal.relationships
        .map { it.target.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TARGETS)
    val known = if (targets.isEmpty()) emptyMap() else store.resourcesTitled(targets, resourceId)
    return validator.validate(input.copy(knownResources = known), proposal)
  }

  /**
   * One more run with the README or model card, when the first left something unproven. Returns the
   * gap run when it keeps more than [first]; null otherwise, and whenever the source, the budget or
   * the model fails: the first run stands.
   */
  private fun gapRound(
    resourceId: UUID,
    material: EnrichmentMaterial,
    first: ValidationResult,
  ): Pair<ValidationResult, GapTarget>? {
    val target = material.gapTarget ?: return null
    val evidence = extraEvidence ?: return null
    if (first.outcome !in GAP_OUTCOMES) return null
    val source = target.kind.label
    // Turned off or spent for today: no README is read for a call that cannot be made.
    if (!gapBudget.hasRoom()) return skipped(source, "skipped_cap")
    val extra =
      try {
        evidence.fetch(target)
      } catch (e: RuntimeException) {
        log.info(
          "gap round of $resourceId: ${target.label} unavailable (${e.javaClass.simpleName})"
        )
        return skipped(source, "source_failed")
      }
    val text = extra?.let { ReadmeText.window(ReadmeText.clean(it.raw)) }
    if (extra == null || text.isNullOrBlank()) return skipped(source, "no_text")
    // The cap counts model calls; reading a README costs none. Reads stay bounded by the
    // enrichments themselves.
    if (!gapBudget.tryAcquire()) return skipped(source, "skipped_cap")
    val input =
      material.input.copy(
        materials =
          material.input.materials +
            Material(
              UUID.randomUUID(),
              extra.sourceUrl,
              extra.retrievedAt,
              text,
              anchorRecordId = target.anchorRecordId,
              supportsClaims = false,
            ),
        // The licence the source API states is a fact, like a record's (a record's own wins): a
        // README that says
        // otherwise conflicts instead of overwriting it.
        sourceFacts =
          extra.license?.let { mapOf("license" to it) + material.input.sourceFacts }
            ?: material.input.sourceFacts,
      )
    val second =
      try {
        run(resourceId, input)
      } catch (e: RuntimeException) {
        log.info("gap round of $resourceId: model run failed (${reason(e)})")
        return skipped(source, "model_failed")
      }
    if (second.accepted <= first.accepted) return skipped(source, "not_better")
    gapMetric(source, "filled")
    return second to target
  }

  private fun skipped(source: String, outcome: String): Pair<ValidationResult, GapTarget>? {
    gapMetric(source, outcome)
    return null
  }

  private fun gapMetric(source: String, outcome: String) =
    meters.counter("helper2.scout.gap", "source", source, "outcome", outcome).increment()
}
