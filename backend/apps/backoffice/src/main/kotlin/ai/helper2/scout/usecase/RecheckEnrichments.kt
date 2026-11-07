package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceRules
import ai.helper2.scout.FieldTargets
import ai.helper2.scout.FieldsByType
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.slf4j.LoggerFactory

/** An AI-written field as stored, with the excerpts that support it. */
data class StoredFieldAnswer(
  val id: UUID,
  val field: String,
  val value: String,
  val origin: ValueOrigin,
  val verification: Verification,
  val excerpts: List<String>,
)

/** A stored summary claim with its excerpts. */
data class StoredClaimAnswer(val id: UUID, val text: String, val excerpts: List<String>)

/** What enrichment left on one resource; [promptVersion] is the prompt of its latest judged run. */
data class StoredAnswer(
  val resourceId: UUID,
  val title: String,
  val type: ResourceType,
  val promptVersion: String?,
  val fields: List<StoredFieldAnswer>,
  val claims: List<StoredClaimAnswer>,
)

/** Outbound port: the stored enrichment answers, and the few changes a re-check may make. */
interface StoredAnswers {
  /** Resources a run judged, by id after [after] (null: from the start), at most [limit]. */
  fun judgedAfter(after: UUID?, limit: Int): List<UUID>

  fun stored(resourceId: UUID): StoredAnswer

  fun rejectFields(ids: List<UUID>): Int

  fun dropClaims(ids: List<UUID>): Int

  /** Back to the enrichment backlog: the unchanged fingerprint does not skip it. */
  fun requestRun(resourceId: UUID)

  /** True the first time [name] is claimed, cluster-wide. */
  fun claimOnce(name: String): Boolean
}

/** What one re-check did. */
data class RecheckResult(
  val checked: Int,
  val fieldsRejected: Int,
  val claimsDropped: Int,
  val runsRequested: Int,
)

/**
 * Re-checks the stored enrichment answers by code, with no model call (ADR 0023). A new prompt
 * version does not re-enrich the catalog (the fingerprint leaves it out); this applies the new
 * rules to what earlier runs stored instead:
 * - a verified pricing, type or use case whose quotes do not state the value is rejected (it shows
 *   "Not stated");
 * - a field an article, a news item or a paper does not keep (pricing, licence, platforms, open
 *   source, type: facts of what it is about, ADR 0023) is rejected, whatever its verification;
 * - a summary claim that fails the claim check (names and numbers in its excerpts, enough of its
 *   words, no superlative, certificate, popularity or comparison) is dropped;
 * - a resource that still misses a target fact of its type (FieldTargets), has more to read (a
 *   README or model card, or its own pages) and was last enriched by an older prompt goes back to
 *   the backlog, where accepted products come first, inside the daily caps.
 *
 * Idempotent: a second run changes nothing. It runs once after the first start ([runOnce], a
 * cluster-wide marker) and whenever an admin asks ([runByAdmin]).
 */
class RecheckEnrichments
@JvmOverloads
constructor(
  private val answers: StoredAnswers,
  private val enrichment: EnrichmentStore,
  private val promptVersion: String,
  /** false without a model: then no resource goes back to the backlog. */
  private val canEnrich: Boolean,
  private val audit: AuditTrail,
  private val meters: MeterRegistry,
  private val batch: Int = BATCH,
) {
  private val log = LoggerFactory.getLogger(RecheckEnrichments::class.java)

  companion object {
    /** The marker of the first re-check, the one the prompt enrich-v5 needs (ADR 0023). */
    const val TASK = "recheck-enrichment-enrich-v5"
    const val BATCH = 200
    private val AI_FACTS = setOf(ValueOrigin.AI_EXTRACTED, ValueOrigin.SELF_DECLARED)
  }

  /** Runs once across the cluster and its restarts; null when it already ran. */
  fun runOnce(): RecheckResult? = if (answers.claimOnce(TASK)) recheckAll() else null

  /** An admin's re-check, recorded in the audit trail. */
  fun runByAdmin(adminId: String): RecheckResult =
    recheckAll().also {
      audit.record(
        adminId,
        AuditTrail.Action.RECHECK_ENRICHMENT,
        null,
        "CHECKED ${it.checked}, REJECTED ${it.fieldsRejected}, DROPPED ${it.claimsDropped}, " +
          "RERUN ${it.runsRequested}",
      )
    }

  private fun recheckAll(): RecheckResult {
    var after: UUID? = null
    var total = RecheckResult(0, 0, 0, 0)
    while (true) {
      val ids = answers.judgedAfter(after, batch)
      if (ids.isEmpty()) break
      for (id in ids) total = total.plus(recheck(id))
      after = ids.last()
    }
    count(total)
    log.info(
      "enrichment re-check: {} resources, {} fields rejected, {} claims dropped, {} runs requested",
      total.checked,
      total.fieldsRejected,
      total.claimsDropped,
      total.runsRequested,
    )
    return total
  }

  private fun recheck(resourceId: UUID): RecheckResult {
    val stored = answers.stored(resourceId)
    val failing = stored.fields.filter { failsTheNewRules(it) || notKeptBy(stored.type, it) }
    val dropped =
      stored.claims.filter {
        EvidenceRules.claimProblem(it.text, it.excerpts, stored.title) != null
      }
    val rejected = answers.rejectFields(failing.map { it.id })
    val gone = answers.dropClaims(dropped.map { it.id })
    val rerun = canEnrich && stillMissesFacts(stored, failing.map { it.id }.toSet())
    if (rerun) answers.requestRun(resourceId)
    return RecheckResult(1, rejected, gone, if (rerun) 1 else 0)
  }

  /** A verified AI fact whose quotes do not state its value under the ADR 0023 rules. */
  private fun failsTheNewRules(f: StoredFieldAnswer): Boolean =
    f.verification == Verification.VERIFIED &&
      f.origin in AI_FACTS &&
      f.field in EvidenceRules.NEWLY_STATED &&
      f.excerpts.none { EvidenceRules.statesValue(it, f.field, f.value) }

  /** An AI field writing does not keep (ADR 0023): a fact of what it is about, not of the page. */
  private fun notKeptBy(type: ResourceType, f: StoredFieldAnswer): Boolean =
    f.verification != Verification.REJECTED &&
      f.origin != ValueOrigin.CURATED &&
      !FieldsByType.allowsField(type, f.field)

  /**
   * True when a target fact is still missing, there is more to read, and the last run predates
   * [promptVersion]: a run under the current prompt already climbed the ladder.
   */
  private fun stillMissesFacts(stored: StoredAnswer, rejected: Set<UUID>): Boolean {
    if (stored.promptVersion == promptVersion) return false
    val material = enrichment.load(stored.resourceId) ?: return false
    if (material.gapTarget == null && material.pageTarget == null) return false
    val verified =
      stored.fields
        .filter { it.verification == Verification.VERIFIED && it.id !in rejected }
        .map { it.field }
        .toSet()
    return FieldTargets.missing(material.type, material.input.sourceFacts, verified).isNotEmpty()
  }

  private fun count(r: RecheckResult) {
    meters.counter("helper2.scout.recheck", "result", "checked").increment(r.checked.toDouble())
    meters
      .counter("helper2.scout.recheck", "result", "fields_rejected")
      .increment(r.fieldsRejected.toDouble())
    meters
      .counter("helper2.scout.recheck", "result", "claims_dropped")
      .increment(r.claimsDropped.toDouble())
    meters
      .counter("helper2.scout.recheck", "result", "runs_requested")
      .increment(r.runsRequested.toDouble())
  }

  private fun RecheckResult.plus(o: RecheckResult) =
    RecheckResult(
      checked + o.checked,
      fieldsRejected + o.fieldsRejected,
      claimsDropped + o.claimsDropped,
      runsRequested + o.runsRequested,
    )
}
