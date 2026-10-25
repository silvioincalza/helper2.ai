package ai.helper2.scout.usecase

import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceValidator
import ai.helper2.scout.LlmUnavailable
import ai.helper2.scout.ValidationResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Runs the model, validates deterministically, persists, and routes problems to review. A resource whose
 * source text is unchanged since its last successful run is not sent again (UNCHANGED): a new star count or
 * version must not cost a model call.
 */
class EnrichResource(
    private val model: EnrichmentModel,
    private val store: EnrichmentStore,
    private val clock: Clock,
    private val validator: EvidenceValidator = EvidenceValidator(),
    private val meters: MeterRegistry = SimpleMeterRegistry(),
) {
    private val log = LoggerFactory.getLogger(EnrichResource::class.java)

    private companion object {
        /** Relationship targets looked up per run; the validator only keeps a few relationships anyway. */
        const val MAX_TARGETS = 20
    }

    /** Class and message only (never the model's answer or the source material), capped for the log and the database. */
    private fun reason(e: RuntimeException): String = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(300)

    /** false when no model is configured: backlog work would only record LLM_UNAVAILABLE again. */
    fun canEnrich(): Boolean = model.available

    fun enrich(resourceId: UUID): EnrichmentOutcome =
        attempt(resourceId).also { meters.counter("helper2.scout.enrichment", "outcome", it.name).increment() }

    private fun attempt(resourceId: UUID): EnrichmentOutcome {
        val started = clock.instant()
        val (input, fingerprint) = store.load(resourceId) ?: return EnrichmentOutcome.REJECTED
        if (store.lastSuccessfulFingerprint(resourceId) == fingerprint) return EnrichmentOutcome.UNCHANGED
        val outcome: EnrichmentOutcome
        val result: ValidationResult?
        try {
            val proposal = model.propose(input)
            val targets = proposal.relationships.map { it.target.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TARGETS)
            val known = if (targets.isEmpty()) emptyMap() else store.resourcesTitled(targets, resourceId)
            result = validator.validate(input.copy(knownResources = known), proposal)
            outcome = result.outcome
        } catch (e: LlmUnavailable) {
            log.warn("enrichment of $resourceId: LLM unavailable (${reason(e)})")
            store.save(resourceId, model, fingerprint, started, null, EnrichmentOutcome.LLM_UNAVAILABLE, reason(e))
            return EnrichmentOutcome.LLM_UNAVAILABLE
        } catch (e: RuntimeException) {
            log.warn("enrichment of $resourceId failed (${reason(e)})")
            store.save(resourceId, model, fingerprint, started, null, EnrichmentOutcome.FAILED, reason(e))
            return EnrichmentOutcome.FAILED
        }
        store.save(resourceId, model, fingerprint, started, result, outcome, null)
        return outcome
    }
}
