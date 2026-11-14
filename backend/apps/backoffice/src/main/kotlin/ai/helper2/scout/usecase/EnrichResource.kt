package ai.helper2.scout.usecase

import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceValidator
import ai.helper2.scout.ExtraEvidence
import ai.helper2.scout.ExtraText
import ai.helper2.scout.FieldTargets
import ai.helper2.scout.GapBudget
import ai.helper2.scout.GapKind
import ai.helper2.scout.GapTarget
import ai.helper2.scout.GapTargets
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

class EnrichResource
@JvmOverloads
constructor(
  private val model: EnrichmentModel,
  private val store: EnrichmentStore,
  private val clock: Clock,
  private val validator: EvidenceValidator = EvidenceValidator(),
  private val meters: MeterRegistry = SimpleMeterRegistry(),
  private val extraEvidence: ExtraEvidence? = null,
  private val gapBudget: GapBudget = GapBudget { false },
  private val officialPages: OfficialPages? = null,
) {
  private val log = LoggerFactory.getLogger(EnrichResource::class.java)

  private companion object {
    const val MAX_TARGETS = 20

    val GAP_OUTCOMES = setOf(EnrichmentOutcome.PARTIAL, EnrichmentOutcome.REJECTED)

    const val PAGE_SOURCE = "official-page"
  }

  private data class Rung(
    val result: ValidationResult,
    val step: String,
    val used: List<String> = emptyList(),
  )

  private fun reason(e: RuntimeException): String =
    "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(300)

  fun canEnrich(): Boolean = model.available

  fun enrich(resourceId: UUID): EnrichmentOutcome =
    attempt(resourceId).also {
      meters.counter("helper2.scout.enrichment", "outcome", it.name).increment()
    }

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
      proposal.relationships.map { it.target.trim() }.filter { it.isNotEmpty() }.distinct()
    if (targets.isEmpty()) return validator.validate(input, proposal)
    return validator.validate(
      input.copy(
        knownResources = store.resourcesTitled(targets.take(MAX_TARGETS), resourceId),
        knownTargets = store.knownTargets(targets),
      ),
      proposal,
    )
  }

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
    return Climbed(better(best, next, "pages", used, PAGE_SOURCE), target to pages)
  }

  private class Climbed(
    val rung: Rung,
    val pagesRead: Pair<PageTarget, List<OfficialPage>>? = null,
  )

  private class Readme(
    val material: Material,
    val target: GapTarget,
    val facts: Map<String, String>,
  )

  private fun needsMore(material: EnrichmentMaterial, result: ValidationResult): Boolean =
    result.outcome in GAP_OUTCOMES ||
      FieldTargets.missing(material.type, material.input.statedBySource, result).isNotEmpty()

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

  private fun runWith(
    resourceId: UUID,
    material: EnrichmentMaterial,
    extra: List<Material>,
    facts: Map<String, String>,
    source: String,
  ): ValidationResult? {
    if (!gapBudget.tryAcquire()) return skipped(source, "skipped_cap")
    val input = material.input
    val withExtra =
      input.copy(
        materials = input.materials + extra,
        sourceFacts = facts + input.sourceFacts,
      )
    return try {
      run(resourceId, withExtra)
    } catch (e: RuntimeException) {
      log.info("gap round of $resourceId ($source): model run failed (${reason(e)})")
      skipped(source, "model_failed")
    }
  }

  private fun readme(resourceId: UUID, material: EnrichmentMaterial): Readme? {
    val target = material.gapTarget ?: return null
    val evidence = extraEvidence ?: return null
    val source = target.kind.label
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
    rememberIfArchived(target, extra)
    val linkedOnly = target.claimedPackage?.let { !GapTargets.namesPackage(extra.raw, it) } ?: false
    if (linkedOnly) return skipped(source, "package_not_named")
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

  private fun rememberIfArchived(target: GapTarget, visited: ExtraText) {
    val archived = visited.archived ?: return
    try {
      store.recordArchived(target.anchorRecordId, archived, visited.retrievedAt)
    } catch (e: RuntimeException) {
      log.warn("visit of ${target.label}: archived not recorded (${e.javaClass.simpleName})")
    }
  }

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

  private fun fieldMetrics(material: EnrichmentMaterial, kept: Rung) {
    val targets = FieldTargets.forType(material.type)
    if (targets.isEmpty()) return
    for (field in targets + EvidenceValidator.JUDGEMENT_FIELDS) {
      val proposed = kept.result.fields.filter { it.field == field }
      val (state, step) =
        when {
          field in material.input.statedBySource -> "verified" to "source"
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
