package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.EvidenceRules
import ai.helper2.scout.FieldTargets
import ai.helper2.scout.FieldsByType
import ai.helper2.scout.RunLock
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.slf4j.LoggerFactory

data class StoredFieldAnswer(
  val id: UUID,
  val field: String,
  val value: String,
  val origin: ValueOrigin,
  val verification: Verification,
  val excerpts: List<String>,
)

data class StoredClaimAnswer(val id: UUID, val text: String, val excerpts: List<String>)

data class StoredAnswer(
  val resourceId: UUID,
  val title: String,
  val type: ResourceType,
  val promptVersion: String?,
  val fields: List<StoredFieldAnswer>,
  val claims: List<StoredClaimAnswer>,
)

interface StoredAnswers {
  fun judgedAfter(after: UUID?, limit: Int): List<UUID>

  fun stored(resourceId: UUID): StoredAnswer

  fun rejectFields(ids: List<UUID>): Int

  fun dropClaims(ids: List<UUID>): Int

  fun requestRun(resourceId: UUID)

  fun closeItemsOfUnstatedValues(): Int = 0

  fun done(name: String): Boolean

  fun markDone(name: String)
}

data class RecheckResult(
  val checked: Int,
  val fieldsRejected: Int,
  val claimsDropped: Int,
  val runsRequested: Int,
  val itemsClosed: Int = 0,
)

class RecheckEnrichments
@JvmOverloads
constructor(
  private val answers: StoredAnswers,
  private val enrichment: EnrichmentStore,
  private val promptVersion: String,
  private val canEnrich: Boolean,
  private val audit: AuditTrail,
  private val meters: MeterRegistry,
  private val lock: RunLock,
  private val batch: Int = BATCH,
) {
  private val log = LoggerFactory.getLogger(RecheckEnrichments::class.java)

  companion object {
    const val TASK = "recheck-enrichment-enrich-v7"
    const val BATCH = 200
    private val ALREADY_DONE = RecheckResult(0, 0, 0, 0)
    private val AI_FACTS = setOf(ValueOrigin.AI_EXTRACTED, ValueOrigin.SELF_DECLARED)
  }

  fun runOnce(): RecheckResult? {
    if (answers.done(TASK)) return null
    val result = lock.runExclusively { recheckOnce() }
    if (result == null) {
      log.info("enrichment re-check left for the next start: the scout-run lock is held")
    }
    return result?.takeIf { it !== ALREADY_DONE }
  }

  private fun recheckOnce(): RecheckResult {
    if (answers.done(TASK)) return ALREADY_DONE
    return recheckAll().also { answers.markDone(TASK) }
  }

  fun runByAdmin(adminId: String): RecheckResult? =
    lock
      .runExclusively { recheckAll() }
      ?.also {
        audit.record(
          adminId,
          AuditTrail.Action.RECHECK_ENRICHMENT,
          null,
          "CHECKED ${it.checked}, REJECTED ${it.fieldsRejected}, DROPPED ${it.claimsDropped}, " +
            "RERUN ${it.runsRequested}, CLOSED ${it.itemsClosed}",
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
    total = total.copy(itemsClosed = answers.closeItemsOfUnstatedValues())
    count(total)
    log.info(
      "enrichment re-check: {} resources, {} fields rejected, {} claims dropped, {} runs requested, " +
        "{} review items closed",
      total.checked,
      total.fieldsRejected,
      total.claimsDropped,
      total.runsRequested,
      total.itemsClosed,
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

  private fun failsTheNewRules(f: StoredFieldAnswer): Boolean =
    f.verification == Verification.VERIFIED &&
      f.origin in AI_FACTS &&
      f.field in EvidenceRules.NEWLY_STATED &&
      f.excerpts.none { EvidenceRules.statesValue(it, f.field, f.value) }

  private fun notKeptBy(type: ResourceType, f: StoredFieldAnswer): Boolean =
    f.verification != Verification.REJECTED &&
      f.origin != ValueOrigin.CURATED &&
      !FieldsByType.allowsField(type, f.field)

  private fun stillMissesFacts(stored: StoredAnswer, rejected: Set<UUID>): Boolean {
    if (stored.promptVersion == promptVersion) return false
    val material = enrichment.load(stored.resourceId) ?: return false
    if (material.gapTarget == null && material.pageTarget == null) return false
    val verified =
      stored.fields
        .filter { it.verification == Verification.VERIFIED && it.id !in rejected }
        .map { it.field }
        .toSet()
    return FieldTargets.missing(material.type, material.input.statedBySource, verified).isNotEmpty()
  }

  private fun count(r: RecheckResult) {
    countClosed(r)
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

  private fun countClosed(r: RecheckResult) =
    meters
      .counter("helper2.scout.recheck", "result", "items_closed")
      .increment(r.itemsClosed.toDouble())

  private fun RecheckResult.plus(o: RecheckResult) =
    RecheckResult(
      checked + o.checked,
      fieldsRejected + o.fieldsRejected,
      claimsDropped + o.claimsDropped,
      runsRequested + o.runsRequested,
    )
}
