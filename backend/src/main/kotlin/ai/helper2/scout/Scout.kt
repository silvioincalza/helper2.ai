package ai.helper2.scout

import ai.helper2.search.MergedResult
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

enum class RunStatus { RUNNING, SUCCEEDED, FAILED, SKIPPED }

data class RunSummary(
    val id: UUID,
    val sourceId: String,
    val status: RunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val itemsSeen: Int,
    val itemsChanged: Int,
    /**
     * Changed resources of this source the model ran on in this run (whatever it answered). Not counted:
     * resources whose source text did not change, skipped calls (provider down, budget spent) and errors.
     * The name is the contract's.
     */
    val enrichmentsQueued: Int,
    val message: String?,
)

/**
 * Collection position for one source. [checkpoint] = start of the last COMPLETE pass (next
 * `updated_since`). While a pass is unfinished, [resumeCursor] continues it and [passStartedAt]
 * becomes the checkpoint once it completes, so items updated mid-pass are not missed.
 */
data class ScoutCursor(val checkpoint: String?, val resumeCursor: String?, val passStartedAt: String?)

/** Outbound port: collection cursors and run history. */
interface ScoutStore {
    fun cursor(sourceId: String): ScoutCursor?
    fun saveCursor(sourceId: String, cursor: ScoutCursor)
    fun save(run: RunSummary)
    fun recent(limit: Int): List<RunSummary>

    /** Closes every RUNNING record as FAILED at [at]; returns how many. */
    fun markInterrupted(at: Instant): Int
}

/** Outbound port: when the last scheduled run ended, seen by every replica. Admin runs are not recorded. */
interface ScheduledRunStore {
    fun lastScheduledRunEnd(): Instant?
    fun scheduledRunEnded(at: Instant)
}

/** What one tick of the schedule did. */
enum class ScheduledRun {
    RAN,

    /** The last scheduled run ended less than a period ago. */
    NOT_DUE,

    /** Another run (another replica's, or an admin's) holds the lock: the next tick tries again. */
    BUSY,
}

/** Outbound port: the catalog's indexing API. Returns resource ids whose source records changed. */
fun interface CatalogIndex {
    fun index(results: List<MergedResult>, sourceId: String): List<UUID>
}

/**
 * Outbound port: at most one Scout run at a time across every replica, whether the schedule or the admin
 * button started it (two runs would collect and enrich the same items twice).
 */
interface RunLock {
    /** Runs [task] and returns its result, or returns null without running it while another run holds the lock. */
    fun <T : Any> runExclusively(task: Supplier<T>): T?
}

/** Outbound port: which resources are worth sending to the LLM, and which still need it. */
interface EnrichmentBacklog {
    /** Resources that still need enrichment (never run, LLM was down, or a retryable failure). */
    fun pending(limit: Int): List<UUID>

    /** The subset of [resourceIds] with enough source text for the model to cite, in the given order. */
    fun eligible(resourceIds: List<UUID>): List<UUID>
}

/** Loads evidence material and persists validated enrichment. Implemented over the catalog. */
interface EnrichmentStore {
    /** null when the resource has no retrievable material. */
    fun load(resourceId: UUID): EnrichmentMaterial?

    /**
     * Input fingerprint of the resource's latest finished run that judged the model's answer (any outcome but
     * LLM_UNAVAILABLE and FAILED); null when there is none.
     */
    fun lastFingerprint(resourceId: UUID): String?

    /** Resources other than [resourceId] titled exactly one of [titles] (case-insensitive), by lower-case title. */
    fun resourcesTitled(titles: Collection<String>, resourceId: UUID): Map<String, UUID>

    /** [failure] is the short technical reason when no model output was validated (null otherwise). */
    fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: Instant, result: ValidationResult?,
             outcome: EnrichmentOutcome, failure: String?)
}
