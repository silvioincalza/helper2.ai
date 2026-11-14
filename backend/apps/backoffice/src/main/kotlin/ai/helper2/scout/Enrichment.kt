package ai.helper2.scout

import ai.helper2.catalog.KnownTarget
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

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
  val extra: Boolean
    get() = anchorRecordId != evidenceId

  val kind: String
    get() =
      when {
        selfDeclared -> "vendor-page"
        extra -> "readme"
        else -> "record"
      }
}

data class EnrichmentInput
@JvmOverloads
constructor(
  val resourceTitle: String,
  val materials: List<Material>,
  val sourceFacts: Map<String, String> = emptyMap(),
  val knownResources: Map<String, UUID> = emptyMap(),
  val type: ResourceType = ResourceType.OTHER,
  val curatedFields: Set<String> = emptySet(),
  val sourceStated: Map<String, Set<String>> = emptyMap(),
  val knownTargets: Map<String, KnownTarget> = emptyMap(),
) {
  val statedBySource: Set<String>
    get() = sourceFacts.keys + sourceStated.filterValues { it.isNotEmpty() }.keys
}

data class EnrichmentMaterial
@JvmOverloads
constructor(
  val input: EnrichmentInput,
  val textHash: String,
  val gapTarget: GapTarget? = null,
  val type: ResourceType = ResourceType.OTHER,
  val pageTarget: PageTarget? = null,
) {
  fun fingerprintFor(model: EnrichmentModel): String =
    sha256Prefix(listOf(textHash, FINGERPRINT_EPOCH, model.model).joinToString("\u001f"))

  companion object {
    const val FINGERPRINT_EPOCH = "enrich-v3"
  }
}

internal fun sha256Prefix(text: String): String =
  HexFormat.of()
    .formatHex(
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)),
      0,
      16,
    )

data class Citation(val evidenceId: String, val excerpt: String)

data class ProposedField(val field: String, val value: String, val citations: List<Citation>)

data class ProposedRelationship
@JvmOverloads
constructor(
  val kind: String,
  val target: String,
  val citations: List<Citation>,
  val targetKind: String? = null,
)

data class ProposedClaim(val text: String, val citations: List<Citation>)

data class EnrichmentProposal(
  val fields: List<ProposedField> = emptyList(),
  val relationships: List<ProposedRelationship> = emptyList(),
  val summary: List<ProposedClaim> = emptyList(),
)

class LlmUnavailable @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

class ModelAnswerInvalid @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

interface EnrichmentModel {
  val provider: String
  val model: String
  val promptVersion: String

  val available: Boolean
    get() = true

  fun propose(input: EnrichmentInput): EnrichmentProposal
}

enum class EnrichmentOutcome {
  VALIDATED,
  PARTIAL,
  REJECTED,
  LLM_UNAVAILABLE,
  FAILED,
  UNCHANGED,
}

data class CheckedCitation
@JvmOverloads
constructor(
  val evidenceId: UUID,
  val excerpt: String,
  val sourceRecordId: UUID = evidenceId,
  val sourceUrl: URI? = null,
  val retrievedAt: Instant? = null,
)

data class CheckedField
@JvmOverloads
constructor(
  val field: String,
  val value: String,
  val verification: Verification,
  val citations: List<CheckedCitation>,
  val origin: ValueOrigin = ValueOrigin.AI_EXTRACTED,
)

data class CheckedRelationship
@JvmOverloads
constructor(
  val kind: String,
  val target: String,
  val targetId: UUID?,
  val verification: Verification,
  val citations: List<CheckedCitation>,
  val origin: ValueOrigin = ValueOrigin.AI_EXTRACTED,
  val knownKind: KnownTarget.Kind? = null,
  val statedKind: String? = null,
)

data class CheckedClaim
@JvmOverloads
constructor(
  val text: String,
  val citations: List<CheckedCitation>,
  val selfDeclared: Boolean = false,
)

data class ValidationResult
@JvmOverloads
constructor(
  val outcome: EnrichmentOutcome,
  val fields: List<CheckedField>,
  val relationships: List<CheckedRelationship>,
  val summary: List<CheckedClaim>,
  val errors: List<String>,
  val forReview: List<String> = errors,
) {
  val accepted: Int
    get() =
      fields.count { it.verification == Verification.VERIFIED } +
        relationships.count { it.verification != Verification.REJECTED } +
        summary.size
}
