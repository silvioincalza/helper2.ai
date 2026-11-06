package ai.helper2.scout

import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * One piece of retrieved source material the model may cite. A source record is its own anchor.
 * Extra text read in the gap round (ADR 0043) has its own evidence id, belongs to the record it was
 * read for ([anchorRecordId]), and may support fields and relationships but not summary claims
 * ([supportsClaims]): a claim is the model's own sentence, and a README is written by anyone. A
 * page of the product's own site (ADR 0056) is [selfDeclared]: what it proves is what the vendor
 * says about itself.
 */
data class Material
@JvmOverloads
constructor(
  val evidenceId: UUID,
  val sourceUrl: URI,
  val retrievedAt: Instant,
  val text: String,
  val anchorRecordId: UUID = evidenceId,
  val supportsClaims: Boolean = true,
  val selfDeclared: Boolean = false,
) {
  /** True for text that is not a stored source record (a README, a model card or a page). */
  val extra: Boolean
    get() = anchorRecordId != evidenceId
}

data class EnrichmentInput(
  val resourceTitle: String,
  val materials: List<Material>,
  /** Values the sources themselves state; the model may not overwrite them. */
  val sourceFacts: Map<String, String> = emptyMap(),
  /**
   * lower-case title -> resource id, for resolving relationship targets; filled once the targets
   * are proposed.
   */
  val knownResources: Map<String, UUID> = emptyMap(),
)

/**
 * What the store loaded for one resource: the model's input, a hash of its source text and, when
 * one of its records names its own repository or model, where the gap round may read more
 * ([gapTarget]). [readmeTarget] is the GitHub repository it names as its own: its README goes into
 * the first model call (ADR 0056). [type] is the catalog's type: it sets the fields the evidence
 * ladder tries to fill (ADR 0056). [pageTarget] is the product's own web page, when one of its
 * records is one.
 */
data class EnrichmentMaterial
@JvmOverloads
constructor(
  val input: EnrichmentInput,
  val textHash: String,
  val gapTarget: GapTarget? = null,
  val type: ResourceType = ResourceType.OTHER,
  val pageTarget: PageTarget? = null,
  val readmeTarget: GapTarget? = gapTarget?.takeIf { it.kind == GapKind.GITHUB_README },
) {
  /**
   * The enrichment fingerprint: the source text, the [FINGERPRINT_EPOCH], the model and, when the
   * first call read one, the README's revision. The same source text under another model is a
   * different input, so it is enriched again; so is a changed README. A new prompt version alone is
   * not (ADR 0056): re-enriching the whole catalog for a wording change costs days of the daily
   * cap. The stored answers are checked again by code instead (RecheckEnrichments), and only
   * resources that still miss facts are sent again.
   */
  @JvmOverloads
  fun fingerprintFor(model: EnrichmentModel, readmeRevision: String? = null): String =
    sha256Prefix(
      listOfNotNull(textHash, FINGERPRINT_EPOCH, model.model, readmeRevision).joinToString("\u001f")
    )

  companion object {
    /**
     * Part of every fingerprint. It is the prompt version the fingerprints had when the prompt
     * version left them (enrich-v3), so stored fingerprints stay valid. Change it only to enrich
     * every resource again on purpose.
     */
    const val FINGERPRINT_EPOCH = "enrich-v3"
  }
}

/** First 16 bytes of the SHA-256 of [text], hex encoded. */
internal fun sha256Prefix(text: String): String =
  HexFormat.of()
    .formatHex(
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)),
      0,
      16,
    )

/** The model must cite evidence by id and quote the supporting excerpt verbatim. */
data class Citation(val evidenceId: String, val excerpt: String)

data class ProposedField(val field: String, val value: String, val citations: List<Citation>)

data class ProposedRelationship(val kind: String, val target: String, val citations: List<Citation>)

data class ProposedClaim(val text: String, val citations: List<Citation>)

/** Schema-constrained model output. Everything in it is a PROPOSAL until validated. */
data class EnrichmentProposal(
  val fields: List<ProposedField> = emptyList(),
  val relationships: List<ProposedRelationship> = emptyList(),
  val summary: List<ProposedClaim> = emptyList(),
)

/**
 * The provider could not be reached or is overloaded (network, timeout, 429, 5xx): retry on any
 * later run.
 */
class LlmUnavailable @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * The provider answered, but the answer is unusable (not the requested JSON, truncated, 4xx):
 * counts as FAILED.
 */
class ModelAnswerInvalid @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/** Outbound port to an LLM (Spring AI adapter in production, fakes in tests). */
interface EnrichmentModel {
  val provider: String
  val model: String
  val promptVersion: String

  /** false for the "no LLM configured" placeholder. */
  val available: Boolean
    get() = true

  /** @throws LlmUnavailable when no model is configured or the provider fails. */
  fun propose(input: EnrichmentInput): EnrichmentProposal
}

/**
 * Result of one enrichment. UNCHANGED is not a run: the source text is the same as for the last
 * successful run, so no model call was made and nothing is recorded.
 */
enum class EnrichmentOutcome {
  VALIDATED,
  PARTIAL,
  REJECTED,
  LLM_UNAVAILABLE,
  FAILED,
  UNCHANGED,
}

/**
 * A citation that passed. For extra text, [sourceRecordId] is the record it belongs to and
 * [sourceUrl] and [retrievedAt] say where and when it was read; for a source record they are null
 * (the record says it).
 */
data class CheckedCitation
@JvmOverloads
constructor(
  val evidenceId: UUID,
  val excerpt: String,
  val sourceRecordId: UUID = evidenceId,
  val sourceUrl: URI? = null,
  val retrievedAt: Instant? = null,
)

/**
 * A field the validator judged. [origin] is AI_EXTRACTED for a fact a quote states, SELF_DECLARED
 * when only the product's own pages state it, and AI_ESTIMATED for a judgement field (audience,
 * tech level) the model may estimate without a quote (ADR 0056). A fact is never AI_ESTIMATED.
 */
data class CheckedField
@JvmOverloads
constructor(
  val field: String,
  val value: String,
  val verification: Verification,
  val citations: List<CheckedCitation>,
  val origin: ValueOrigin = ValueOrigin.AI_EXTRACTED,
)

/** [origin] is SELF_DECLARED when only the product's own pages name the target. */
data class CheckedRelationship
@JvmOverloads
constructor(
  val kind: String,
  val target: String,
  val targetId: UUID?,
  val verification: Verification,
  val citations: List<CheckedCitation>,
  val origin: ValueOrigin = ValueOrigin.AI_EXTRACTED,
)

/** [selfDeclared]: the claim cites the product's own page. */
data class CheckedClaim
@JvmOverloads
constructor(
  val text: String,
  val citations: List<CheckedCitation>,
  val selfDeclared: Boolean = false,
)

data class ValidationResult(
  val outcome: EnrichmentOutcome,
  val fields: List<CheckedField>,
  val relationships: List<CheckedRelationship>,
  /** Only claims that passed validation. */
  val summary: List<CheckedClaim>,
  val errors: List<String>,
) {
  /**
   * What the run keeps: verified fields, relationships not rejected, and claims. The gap round
   * keeps the run with more. An AI-estimated value proves nothing, so it does not count.
   */
  val accepted: Int
    get() =
      fields.count { it.verification == Verification.VERIFIED } +
        relationships.count { it.verification != Verification.REJECTED } +
        summary.size
}
