package ai.helper2.scout.usecase

import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceValidator
import ai.helper2.scout.ExtraEvidence
import ai.helper2.scout.FieldTargets
import ai.helper2.scout.GapBudget
import ai.helper2.scout.GapKind
import ai.helper2.scout.GapTarget
import ai.helper2.scout.LlmUnavailable
import ai.helper2.scout.Material
import ai.helper2.scout.OfficialPage
import ai.helper2.scout.OfficialPages
import ai.helper2.scout.PageTarget
import ai.helper2.scout.UntrustedPageText
import ai.helper2.scout.ValidationResult
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Runs the model, validates deterministically, persists, and routes problems to review. A resource
 * whose enrichment fingerprint (source text, prompt version, model) equals that of its last judged
 * run is not sent again (UNCHANGED): a new star count or version must not cost a model call, and a
 * REJECTED answer is not asked for twice. A new prompt version or model changes the fingerprint, so
 * the resource is enriched again.
 *
 * Evidence ladder (ADR 0023): when the first run leaves a target field of the type unproven, or
 * ends PARTIAL or REJECTED, the attempt climbs one or two steps, each one more model call: the
 * README or model card the resource names ([extraEvidence]), then the product's own pages
 * ([officialPages]). At most three model calls per attempt. Each step keeps the run that keeps
 * more, and the attempt saves once, under the fingerprint of the source text alone: the read text
 * is not stored, so the next run with unchanged source text is UNCHANGED, and the ladder runs once
 * per fingerprint.
 */
class EnrichResource
@JvmOverloads
constructor(
  private val model: EnrichmentModel,
  private val store: EnrichmentStore,
  private val clock: Clock,
  private val validator: EvidenceValidator = EvidenceValidator(),
  private val meters: MeterRegistry = SimpleMeterRegistry(),
  /** null: no README step (no source adapters, e.g. the demo profile). */
  private val extraEvidence: ExtraEvidence? = null,
  private val gapBudget: GapBudget = GapBudget { false },
  /** null: no page step (Tavily Extract off or without a key). */
  private val officialPages: OfficialPages? = null,
) {
  private val log = LoggerFactory.getLogger(EnrichResource::class.java)

  private companion object {
    /**
     * Relationship targets looked up per run; the validator only keeps a few relationships anyway.
     */
    const val MAX_TARGETS = 20

    val GAP_OUTCOMES = setOf(EnrichmentOutcome.PARTIAL, EnrichmentOutcome.REJECTED)

    const val PAGE_SOURCE = "official-page"
  }

  /** The run an attempt keeps, the step that produced it, and the extra text it read. */
  private data class Rung(
    val result: ValidationResult,
    val step: String,
    val used: List<String> = emptyList(),
  )

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

  /**
   * true when the last run already judged this source text with this model, so a run now would end
   * UNCHANGED. Reads the material and the stored fingerprint only: no model call.
   */
  fun unchanged(resourceId: UUID): Boolean {
    val material = store.load(resourceId) ?: return false
    return store.lastFingerprint(resourceId) == material.fingerprintFor(model)
  }

  private fun attempt(resourceId: UUID): EnrichmentOutcome {
    val started = clock.instant()
    val material = store.load(resourceId) ?: return EnrichmentOutcome.REJECTED
    val fingerprint = material.fingerprintFor(model)
    if (store.lastFingerprint(resourceId) == fingerprint) return EnrichmentOutcome.UNCHANGED
    val first =
      try {
        run(resourceId, material.input)
      } catch (e: LlmUnavailable) {
        log.warn("enrichment of $resourceId: LLM unavailable (${reason(e)})")
        return saveFailure(resourceId, fingerprint, started, EnrichmentOutcome.LLM_UNAVAILABLE, e)
      } catch (e: RuntimeException) {
        log.warn("enrichment of $resourceId failed (${reason(e)})")
        return saveFailure(resourceId, fingerprint, started, EnrichmentOutcome.FAILED, e)
      }
    val climbed = climb(resourceId, material, Rung(first, "records"))
    val kept = climbed.rung
    val used = kept.used.joinToString(" + ").take(300).ifEmpty { null }
    store.save(
      resourceId,
      model,
      fingerprint,
      started,
      kept.result,
      kept.result.outcome,
      null,
      used,
    )
    climbed.pagesRead?.let { (target, pages) -> officialPages?.markRead(target, pages) }
    fieldMetrics(material, kept)
    return kept.result.outcome
  }

  /** Records a run that validated no model output, and returns its [outcome]. */
  private fun saveFailure(
    resourceId: UUID,
    fingerprint: String,
    started: Instant,
    outcome: EnrichmentOutcome,
    e: RuntimeException,
  ): EnrichmentOutcome {
    store.save(resourceId, model, fingerprint, started, null, outcome, reason(e), null)
    return outcome
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
   * The ladder after the first run: the README or model card, then the product's own pages, each
   * only while something is still unproven. Returns the rung that keeps most.
   */
  private fun climb(resourceId: UUID, material: EnrichmentMaterial, first: Rung): Climbed {
    if (!needsMore(material, first.result)) return Climbed(first)
    val readme = readme(resourceId, material)
    var best = first
    if (readme != null) {
      val source = readme.target.kind.label
      val next = runWith(resourceId, material, listOf(readme.material), readme.facts, source)
      best = better(best, next, "readme", listOf(readme.target.label), source)
    }
    val target = material.pageTarget ?: return Climbed(best)
    if (!needsMore(material, best.result)) return Climbed(best)
    val pages = readPages(resourceId, target) ?: return Climbed(best)
    val extra = listOfNotNull(readme?.material) + pages.map { pageMaterial(it, target) }
    val facts = readme?.facts.orEmpty()
    val used =
      listOfNotNull(readme?.target?.label) + "${GapKind.OFFICIAL_PAGE.label}:${target.url.host}"
    val next = runWith(resourceId, material, extra, facts, PAGE_SOURCE) ?: return Climbed(best)
    // The model read the pages: once the run is saved they go in the 30-day ledger.
    return Climbed(better(best, next, "pages", used, PAGE_SOURCE), target to pages)
  }

  /** The rung to save and, when a model call read them, the product pages it read. */
  private class Climbed(
    val rung: Rung,
    val pagesRead: Pair<PageTarget, List<OfficialPage>>? = null,
  )

  /** The README or model card read for [target], and the licence its source API states. */
  private class Readme(
    val material: Material,
    val target: GapTarget,
    val facts: Map<String, String>,
  )

  /** A target fact is still missing, or the run left something unproven (ADR 0023). */
  private fun needsMore(material: EnrichmentMaterial, result: ValidationResult): Boolean =
    result.outcome in GAP_OUTCOMES ||
      FieldTargets.missing(material.type, material.input.sourceFacts, result).isNotEmpty()

  /** [next] when it keeps more than [best]; [best] otherwise. */
  private fun better(
    best: Rung,
    next: ValidationResult?,
    step: String,
    used: List<String>,
    source: String,
  ): Rung {
    if (next == null) return best
    if (next.accepted <= best.result.accepted) return best.also { gapMetric(source, "not_better") }
    gapMetric(source, "filled")
    return Rung(next, step, used)
  }

  /**
   * One more model call with [extra] text next to the records, inside the gap budget; null when the
   * budget is spent or the model fails: the earlier run stands.
   */
  private fun runWith(
    resourceId: UUID,
    material: EnrichmentMaterial,
    extra: List<Material>,
    facts: Map<String, String>,
    source: String,
  ): ValidationResult? {
    // The cap counts model calls; reading costs none of it.
    if (!gapBudget.tryAcquire()) return skipped(source, "skipped_cap")
    val input = material.input
    val withExtra =
      input.copy(
        materials = input.materials + extra,
        // The licence the source API states is a fact, like a record's (a record's own wins): text
        // that says otherwise conflicts instead of overwriting it.
        sourceFacts = facts + input.sourceFacts,
      )
    return try {
      run(resourceId, withExtra)
    } catch (e: RuntimeException) {
      log.info("gap round of $resourceId ($source): model run failed (${reason(e)})")
      skipped(source, "model_failed")
    }
  }

  /** The README or model card as a material that proves no claim alone, or null. */
  private fun readme(resourceId: UUID, material: EnrichmentMaterial): Readme? {
    val target = material.gapTarget ?: return null
    val evidence = extraEvidence ?: return null
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
    if (extra == null) return skipped(source, "no_text")
    val text = UntrustedPageText.window(UntrustedPageText.clean(extra.raw))
    if (text.isBlank()) return skipped(source, "no_text")
    val readme =
      Material(
        UUID.randomUUID(),
        extra.sourceUrl,
        extra.retrievedAt,
        text,
        anchorRecordId = target.anchorRecordId,
        supportsClaims = false,
      )
    return Readme(readme, target, extra.license?.let { mapOf("license" to it) }.orEmpty())
  }

  /**
   * The product's own pages, or null when none could be read. As materials they are self-declared;
   * only a page on the accepted page's own host may support a summary claim. Pages read within the
   * last 24 hours come from the text cache and cost no credit (outcome "cached").
   */
  private fun readPages(resourceId: UUID, target: PageTarget): List<OfficialPage>? {
    val pages = officialPages ?: return null
    if (!gapBudget.hasRoom()) return skipped(PAGE_SOURCE, "skipped_cap")
    val reading =
      try {
        pages.read(target)
      } catch (e: RuntimeException) {
        log.info("gap round of $resourceId: pages unavailable (${e.javaClass.simpleName})")
        return skipped(PAGE_SOURCE, "source_failed")
      }
    if (reading.pages.isEmpty()) return skipped(PAGE_SOURCE, reading.outcome)
    if (reading.outcome == "cached") gapMetric(PAGE_SOURCE, "cached")
    return reading.pages
  }

  private fun pageMaterial(page: OfficialPage, target: PageTarget): Material =
    Material(
      UUID.randomUUID(),
      page.url,
      page.retrievedAt,
      page.text,
      anchorRecordId = target.anchorRecordId,
      supportsClaims = page.startHost,
      selfDeclared = true,
    )

  private fun <T> skipped(source: String, outcome: String): T? {
    gapMetric(source, outcome)
    return null
  }

  private fun gapMetric(source: String, outcome: String) =
    meters.counter("helper2.scout.gap", "source", source, "outcome", outcome).increment()

  /**
   * helper2.scout.fields{type,field,state,step}: per target and judgement field, whether the kept
   * run verified it, estimated it, rejected it or left it unknown. A fact the source states is
   * verified at step "source".
   */
  private fun fieldMetrics(material: EnrichmentMaterial, kept: Rung) {
    val targets = FieldTargets.forType(material.type)
    if (targets.isEmpty()) return
    for (field in targets + EvidenceValidator.JUDGEMENT_FIELDS) {
      val proposed = kept.result.fields.filter { it.field == field }
      val (state, step) =
        when {
          field in material.input.sourceFacts -> "verified" to "source"
          proposed.any { it.verification == Verification.VERIFIED } -> "verified" to kept.step
          proposed.any { it.origin == ValueOrigin.AI_ESTIMATED } -> "ai_estimated" to kept.step
          proposed.isNotEmpty() -> "rejected" to kept.step
          else -> "unknown" to kept.step
        }
      meters
        .counter(
          "helper2.scout.fields",
          "type",
          material.type.name,
          "field",
          field,
          "state",
          state,
          "step",
          step,
        )
        .increment()
    }
  }
}
