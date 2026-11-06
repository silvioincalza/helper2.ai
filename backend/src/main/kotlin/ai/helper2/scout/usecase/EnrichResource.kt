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
 * whose enrichment fingerprint (source text, epoch, model, README revision) equals that of its last
 * judged run is not sent again (UNCHANGED): a new star count or version must not cost a model call,
 * and a REJECTED answer is not asked for twice. A new model or a changed README changes the
 * fingerprint, so the resource is enriched again.
 *
 * README first (ADR 0056): when a record names the resource's own GitHub repository, its README
 * ([extraEvidence]) goes into the first model call, and its revision into the fingerprint: a
 * changed README enriches the resource again, an unchanged one is UNCHANGED. The read is
 * conditional, so an unchanged README costs no GitHub rate limit. When GitHub fails, the first call
 * reads the records alone and the run's errors say so; the README is not tried again in the
 * attempt.
 *
 * Evidence ladder (ADR 0043, ADR 0056): when the first run leaves a target field of the type
 * unproven, or ends PARTIAL or REJECTED, the attempt climbs one or two steps, each one more model
 * call: the model card the resource names, then the product's own pages ([officialPages]). At most
 * three model calls per attempt. Each step keeps the run that keeps more, and the attempt saves
 * once, under the fingerprint of the source text and the README's revision: no read text is stored,
 * so the next run with the same inputs is UNCHANGED, and the ladder runs once per fingerprint.
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

    const val RECORDS_ALONE = "enriched from the records alone"
  }

  /** The run an attempt keeps, the step that produced it, and the extra text it read. */
  private data class Rung(
    val result: ValidationResult,
    val step: String,
    val used: List<String> = emptyList(),
  ) {
    /** The rung with [note] in its errors, for the run's audit. */
    fun noting(note: String?): Rung =
      if (note == null) this else copy(result = result.copy(errors = result.errors + note))
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
    val checked = checkReadme(resourceId, material)
    if (store.lastFingerprint(resourceId) == material.fingerprintFor(model, checked.revision)) {
      return EnrichmentOutcome.UNCHANGED
    }
    val firstReadme = withText(resourceId, checked)
    val readme = firstReadme.readable()
    val fingerprint = material.fingerprintFor(model, firstReadme.revision)
    val first =
      try {
        run(resourceId, withExtra(material.input, listOfNotNull(readme)))
      } catch (e: LlmUnavailable) {
        log.warn("enrichment of $resourceId: LLM unavailable (${reason(e)})")
        return saveFailure(resourceId, fingerprint, started, EnrichmentOutcome.LLM_UNAVAILABLE, e)
      } catch (e: RuntimeException) {
        log.warn("enrichment of $resourceId failed (${reason(e)})")
        return saveFailure(resourceId, fingerprint, started, EnrichmentOutcome.FAILED, e)
      }
    val firstRung =
      if (readme == null) Rung(first, "records")
      else Rung(first, "readme", listOf(readme.target.label))
    val climbed = climb(resourceId, material, firstRung, readme)
    val kept = climbed.rung.noting(firstReadme.failure)
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
   * The ladder after the first run: the model card, then the product's own pages, each only while
   * something is still unproven. A GitHub README is never a step: the first run read it ([readme]),
   * or GitHub failed and is not asked again. Returns the rung that keeps most.
   */
  private fun climb(
    resourceId: UUID,
    material: EnrichmentMaterial,
    first: Rung,
    readme: Readme?,
  ): Climbed {
    if (!needsMore(material, first.result)) return Climbed(first)
    var best = first
    val card = modelCard(resourceId, material)
    if (card != null) {
      val source = card.target.kind.label
      val texts = listOfNotNull(readme, card)
      val next = runWith(resourceId, material, texts, source)
      best = better(best, next, "readme", texts.map { it.target.label }, source)
    }
    val target = material.pageTarget ?: return Climbed(best)
    if (!needsMore(material, best.result)) return Climbed(best)
    val pages = readPages(resourceId, target) ?: return Climbed(best)
    val texts = listOfNotNull(readme, card)
    val used = texts.map { it.target.label } + "${GapKind.OFFICIAL_PAGE.label}:${target.url.host}"
    val next =
      runWith(resourceId, material, texts, PAGE_SOURCE, pages.map { pageMaterial(it, target) })
        ?: return Climbed(best)
    // The model read the pages: once the run is saved they go in the 30-day ledger.
    return Climbed(better(best, next, "pages", used, PAGE_SOURCE), target to pages)
  }

  /** The rung to save and, when a model call read them, the product pages it read. */
  private class Climbed(
    val rung: Rung,
    val pagesRead: Pair<PageTarget, List<OfficialPage>>? = null,
  )

  /**
   * [input] with [texts] next to the records. The licence the source API states is a fact, like a
   * record's (a record's own wins; the first text's wins over a later one): text that says
   * otherwise conflicts instead of overwriting it.
   */
  private fun withExtra(
    input: EnrichmentInput,
    texts: List<Readme>,
    pages: List<Material> = emptyList(),
  ): EnrichmentInput {
    if (texts.isEmpty() && pages.isEmpty()) return input
    val facts = texts.reversed().fold(emptyMap<String, String>()) { acc, t -> acc + t.facts }
    return input.copy(
      materials = input.materials + texts.map { it.material } + pages,
      sourceFacts = facts + input.sourceFacts,
    )
  }

  /** A target fact is still missing, or the run left something unproven (ADR 0043). */
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
    texts: List<Readme>,
    source: String,
    pages: List<Material> = emptyList(),
  ): ValidationResult? {
    // The cap counts model calls; reading costs none of it.
    if (!gapBudget.tryAcquire()) return skipped(source, "skipped_cap")
    return try {
      run(resourceId, withExtra(material.input, texts, pages))
    } catch (e: RuntimeException) {
      log.info("gap round of $resourceId ($source): model run failed (${reason(e)})")
      skipped(source, "model_failed")
    }
  }

  /**
   * The README of the resource's GitHub repository, read before the first model call (ADR 0056).
   * The read may answer "not modified": then only the revision is known. Not under the gap cap: it
   * makes no model call of its own.
   */
  private fun checkReadme(resourceId: UUID, material: EnrichmentMaterial): FirstReadme {
    val target = material.readmeTarget ?: return FirstReadme()
    val evidence = extraEvidence ?: return FirstReadme()
    val check =
      try {
        evidence.check(target)
      } catch (e: RuntimeException) {
        return unread(resourceId, target, e)
      }
    if (check == null) return FirstReadme(target).also { readmeMetric("missing") }
    readmeMetric(if (check.notModified) "cached" else "read")
    return FirstReadme(target, check.revision, check.text)
  }

  /** [checked] with its text: after a 304 the call needs the README, so it is read in full. */
  private fun withText(resourceId: UUID, checked: FirstReadme): FirstReadme {
    val target = checked.target
    if (target == null || checked.revision == null || checked.text != null) return checked
    val text =
      try {
        extraEvidence?.fetch(target)
      } catch (e: RuntimeException) {
        return unread(resourceId, target, e)
      }
    if (text == null) return FirstReadme(target).also { readmeMetric("missing") }
    readmeMetric("read")
    return FirstReadme(target, text.revisionOrHash, text)
  }

  /** GitHub failed or rate-limited: the first call reads the records alone, and the run says so. */
  private fun unread(resourceId: UUID, target: GapTarget, e: RuntimeException): FirstReadme {
    log.info("enrichment of $resourceId: ${target.label} unavailable (${e.javaClass.simpleName})")
    readmeMetric("failed")
    return FirstReadme(
      target,
      failure = "${target.label} not read (${e.javaClass.simpleName}): $RECORDS_ALONE",
    )
  }

  /**
   * The model card as a material that proves no claim alone, or null. A GitHub README is never read
   * here: the first call read it, or GitHub failed and is not asked again in the attempt.
   */
  private fun modelCard(resourceId: UUID, material: EnrichmentMaterial): Readme? {
    val target = material.gapTarget?.takeIf { it.kind != GapKind.GITHUB_README } ?: return null
    val evidence = extraEvidence ?: return null
    val source = target.kind.label
    // Turned off or spent for today: no card is read for a call that cannot be made.
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
    return readmeOf(target, extra) ?: skipped(source, "no_text")
  }

  private fun readmeMetric(outcome: String) =
    meters.counter("helper2.scout.readme", "outcome", outcome).increment()

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

/** The README or model card read for [target], and the licence its source API states. */
private class Readme(
  val material: Material,
  val target: GapTarget,
  val facts: Map<String, String>,
)

/**
 * The README read before the first model call (ADR 0056). [target] is the repository it was read
 * for (null: the resource names none, or there is no adapter). [revision] goes into the
 * fingerprint: null when there is no README or it could not be read. [text] is null after a 304
 * until the call needs it. [failure] says in the run's errors why the README is missing.
 */
private class FirstReadme(
  val target: GapTarget? = null,
  val revision: String? = null,
  val text: ExtraText? = null,
  val failure: String? = null,
) {
  /** The README as material, or null when there is none or it cleans to nothing. */
  fun readable(): Readme? {
    val t = target ?: return null
    return text?.let { readmeOf(t, it) }
  }
}

/** [extra] cleaned and cut to the window, as a material that proves no claim alone; or null. */
private fun readmeOf(target: GapTarget, extra: ExtraText): Readme? {
  val text = UntrustedPageText.window(UntrustedPageText.clean(extra.raw))
  if (text.isBlank()) return null
  val material =
    Material(
      UUID.randomUUID(),
      extra.sourceUrl,
      extra.retrievedAt,
      text,
      anchorRecordId = target.anchorRecordId,
      supportsClaims = false,
    )
  return Readme(material, target, extra.license?.let { mapOf("license" to it) }.orEmpty())
}
