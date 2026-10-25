package ai.helper2.scout

import ai.helper2.shared.Audience
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.TechLevel
import ai.helper2.shared.Verification
import java.net.URI
import java.time.Instant
import java.util.UUID

/** One piece of retrieved source material the model may cite. */
data class Material(val evidenceId: UUID, val sourceUrl: URI, val retrievedAt: Instant, val text: String)

data class EnrichmentInput(
    val resourceTitle: String,
    val materials: List<Material>,
    /** Values the sources themselves state; the model may not overwrite them. */
    val sourceFacts: Map<String, String> = emptyMap(),
    /** lower-case title -> resource id, for resolving relationship targets; filled once the targets are proposed. */
    val knownResources: Map<String, UUID> = emptyMap(),
)

/** What the store loaded for one resource: the model's input and a fingerprint of its source text. */
data class EnrichmentMaterial(val input: EnrichmentInput, val fingerprint: String)

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

/** The provider could not be reached or is overloaded (network, timeout, 429, 5xx): retry on any later run. */
class LlmUnavailable @JvmOverloads constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The provider answered, but the answer is unusable (not the requested JSON, truncated, 4xx): counts as FAILED. */
class ModelAnswerInvalid @JvmOverloads constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Outbound port to an LLM (Spring AI adapter in production, fakes in tests). */
interface EnrichmentModel {
    val provider: String
    val model: String
    val promptVersion: String

    /** false for the "no LLM configured" placeholder. */
    val available: Boolean get() = true

    /** @throws LlmUnavailable when no model is configured or the provider fails. */
    fun propose(input: EnrichmentInput): EnrichmentProposal
}

/**
 * Result of one enrichment. UNCHANGED is not a run: the source text is the same as for the last successful
 * run, so no model call was made and nothing is recorded.
 */
enum class EnrichmentOutcome { VALIDATED, PARTIAL, REJECTED, LLM_UNAVAILABLE, FAILED, UNCHANGED }

data class CheckedCitation(val evidenceId: UUID, val excerpt: String)

data class CheckedField(val field: String, val value: String, val verification: Verification, val citations: List<CheckedCitation>)
data class CheckedRelationship(val kind: String, val target: String, val targetId: UUID?, val verification: Verification, val citations: List<CheckedCitation>)
data class CheckedClaim(val text: String, val citations: List<CheckedCitation>)

data class ValidationResult(
    val outcome: EnrichmentOutcome,
    val fields: List<CheckedField>,
    val relationships: List<CheckedRelationship>,
    /** Only claims that passed validation. */
    val summary: List<CheckedClaim>,
    val errors: List<String>,
) {
    val needsReview: Boolean
        get() = errors.isNotEmpty() ||
            fields.any { it.verification != Verification.VERIFIED } ||
            relationships.any { it.verification != Verification.VERIFIED }
}

/**
 * Deterministic validation of model output. The guarantee: nothing the model says is published
 * as VERIFIED unless (1) it is in the allowed schema, (2) it cites evidence that was actually
 * retrieved, (3) every cited excerpt appears in that evidence and, for license, openSource and platforms,
 * (4) the excerpt states the value itself (or a known synonym). Model confidence is ignored.
 */
class EvidenceValidator(private val minExcerpt: Int = MIN_EXCERPT_CHARS, private val maxExcerpt: Int = MAX_EXCERPT_CHARS) {

    companion object {
        /**
         * 20 characters is about three words: enough to state a fact ("Works with Claude Desktop"), too long for
         * a bare token ("MCP", "free") that appears in almost any material and so proves nothing.
         */
        const val MIN_EXCERPT_CHARS = 20
        const val MAX_EXCERPT_CHARS = 300

        /** Summary claims kept per run; the prompt asks for the same number. */
        const val MAX_SUMMARY_CLAIMS = 3

        val RELATIONSHIP_KINDS = setOf("INTEGRATES_WITH", "USES", "SUPPORTS", "COMPATIBLE_WITH")
        val USE_CASES = setOf(
            "writing", "translation", "summarization", "image-generation", "coding", "research", "transcription",
            "automation", "design", "learning", "data-analysis", "chat", "video", "audio",
        )
        val PLATFORMS = setOf("web", "desktop", "ios", "android", "cli", "api", "browser-extension", "vscode", "node", "python", "local", "remote")

        /**
         * The allowed values, one line per field, for the model's instructions. Built from the same
         * sets the validator checks, so the prompt and the validation cannot drift apart.
         */
        @JvmStatic
        fun allowedValues(): String = listOf(
            "type" to ResourceType.entries.map { it.name },
            "audience" to Audience.entries.filter { it != Audience.UNKNOWN }.map { it.name },
            "techLevel" to TechLevel.entries.map { it.name },
            "pricing" to Pricing.entries.map { it.name },
            "openSource" to listOf("true", "false"),
            "license" to listOf("an SPDX licence id, e.g. MIT, Apache-2.0"),
            "useCase" to USE_CASES.toList(),
            "platforms" to PLATFORMS.toList(),
            "relationship kind" to RELATIONSHIP_KINDS.toList(),
        ).joinToString("\n") { (field, values) -> "- $field: ${values.joinToString(" | ")}" }

        private val OSI_LICENCE_WORDS = listOf("mit", "apache", "bsd", "gpl", "lgpl", "agpl", "mpl", "isc", "unlicense")

        /** Words that state a value of a field whose value must appear in its excerpt. */
        private val SYNONYMS: Map<String, Map<String, List<String>>> = mapOf(
            "openSource" to mapOf(
                "true" to listOf("open source", "open-source", "opensource") + OSI_LICENCE_WORDS,
                "false" to listOf("proprietary", "closed source", "closed-source", "source-available", "source available"),
            ),
            "platforms" to mapOf(
                "web" to listOf("web", "browser", "online"),
                "desktop" to listOf("desktop", "windows", "macos", "linux"),
                "ios" to listOf("ios", "iphone", "ipad"),
                "android" to listOf("android"),
                "cli" to listOf("cli", "command line", "command-line", "terminal"),
                "api" to listOf("api", "rest", "sdk"),
                "browser-extension" to listOf("browser extension", "chrome extension", "firefox extension", "extension", "add-on"),
                "vscode" to listOf("vscode", "vs code", "visual studio code"),
                "node" to listOf("node", "node.js", "nodejs", "npm"),
                "python" to listOf("python", "pip", "pypi"),
                "local" to listOf("local", "locally", "offline", "self-hosted", "on-device", "on device"),
                "remote" to listOf("remote", "hosted", "cloud"),
            ),
        )

        private val MUST_STATE_VALUE = setOf("license", "openSource", "platforms")

        /**
         * Words that state [value] of [field]: the value itself and its synonyms. A licence is stated by its
         * family name ("Apache-2.0" by "apache", "GPL-3.0" also by "general public license").
         */
        internal fun statingWords(field: String, value: String): List<String> {
            val v = value.lowercase()
            if (field == "license") {
                val family = v.substringBefore('-').substringBefore('.').trimEnd { it.isDigit() }.ifEmpty { v }
                return listOf(v, family) + when (family) {
                    "gpl" -> listOf("general public license")
                    "lgpl" -> listOf("lesser general public license")
                    "agpl" -> listOf("affero general public license")
                    "mpl" -> listOf("mozilla public license")
                    else -> emptyList()
                }
            }
            return listOf(v) + SYNONYMS[field]?.get(v).orEmpty()
        }
    }

    private val relationshipKinds = RELATIONSHIP_KINDS
    private val useCases = USE_CASES
    private val platforms = PLATFORMS
    private val spdx = Regex("^[A-Za-z0-9.+-]{2,40}$")

    private val allowed: Map<String, (String) -> Boolean> = mapOf(
        "type" to { v -> ResourceType.entries.any { it.name == v } },
        "audience" to { v -> Audience.entries.any { it.name == v && it != Audience.UNKNOWN } },
        "techLevel" to { v -> TechLevel.entries.any { it.name == v } },
        "pricing" to { v -> Pricing.entries.any { it.name == v } },
        "openSource" to { v -> v == "true" || v == "false" },
        "license" to { v -> spdx.matches(v) },
        "useCase" to { v -> v in useCases },
        "platforms" to { v -> v in platforms },
    )

    fun validate(input: EnrichmentInput, proposal: EnrichmentProposal): ValidationResult {
        val errors = mutableListOf<String>()
        val byId = input.materials.associateBy { it.evidenceId.toString() }

        fun check(citations: List<Citation>, what: String): List<CheckedCitation>? {
            if (citations.isEmpty()) {
                errors += "$what: no evidence cited"
                return null
            }
            val out = mutableListOf<CheckedCitation>()
            for (c in citations) {
                val m = byId[c.evidenceId]
                if (m == null) {
                    errors += "$what: unknown evidence id ${c.evidenceId.take(40)}"
                    return null
                }
                val excerpt = c.excerpt.trim()
                if (excerpt.length !in minExcerpt..maxExcerpt) {
                    errors += "$what: excerpt length ${excerpt.length} outside $minExcerpt..$maxExcerpt"
                    return null
                }
                if (!normalize(m.text).contains(normalize(excerpt))) {
                    errors += "$what: excerpt not found in cited evidence"
                    return null
                }
                out += CheckedCitation(m.evidenceId, excerpt)
            }
            return out
        }

        val fields = proposal.fields.map { f ->
            val label = "field ${f.field.take(40)}"
            val rule = allowed[f.field]
            when {
                rule == null -> {
                    errors += "$label: field not allowed"
                    CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
                }
                !rule(f.value) -> {
                    errors += "$label: value not allowed"
                    CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
                }
                else -> {
                    val cited = check(f.citations, label)
                    val sourceValue = input.sourceFacts[f.field]
                    when {
                        cited == null -> CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
                        sourceValue != null && !sourceValue.equals(f.value, ignoreCase = true) -> {
                            errors += "$label: conflicts with source-provided value"
                            CheckedField(f.field, f.value, Verification.CONFLICTING, cited)
                        }
                        f.field in MUST_STATE_VALUE && cited.none { statesValue(it.excerpt, f.field, f.value) } -> {
                            errors += "$label: the cited excerpt does not state the value"
                            CheckedField(f.field, f.value, Verification.UNVERIFIED, cited)
                        }
                        else -> CheckedField(f.field, f.value, Verification.VERIFIED, cited)
                    }
                }
            }
        }

        val relationships = proposal.relationships.map { r ->
            val label = "relationship ${r.kind.take(20)} ${r.target.take(40)}"
            if (r.kind !in relationshipKinds || r.target.isBlank() || r.target.length > 120) {
                errors += "$label: invalid kind or target"
                return@map CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
            }
            val cited = check(r.citations, label)
            if (cited == null) {
                return@map CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
            }
            if (cited.none { normalize(it.excerpt).contains(normalize(r.target)) }) {
                errors += "$label: excerpt does not mention the target"
                return@map CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
            }
            val targetId = input.knownResources[r.target.trim().lowercase()]
            CheckedRelationship(r.kind, r.target, targetId, if (targetId != null) Verification.VERIFIED else Verification.UNVERIFIED, cited)
        }

        val claims = proposal.summary.take(MAX_SUMMARY_CLAIMS).mapNotNull { c ->
            if (c.text.isBlank() || c.text.length > 300) {
                errors += "summary claim: empty or too long"
                return@mapNotNull null
            }
            check(c.citations, "summary claim")?.let { CheckedClaim(c.text.trim(), it) }
        }

        val proposed = proposal.fields.size + proposal.relationships.size + proposal.summary.size
        val accepted = fields.count { it.verification == Verification.VERIFIED } +
            relationships.count { it.verification != Verification.REJECTED } + claims.size
        val outcome = when {
            proposed == 0 -> EnrichmentOutcome.REJECTED.also { errors += "empty proposal" }
            accepted == 0 -> EnrichmentOutcome.REJECTED
            errors.isEmpty() && relationships.none { it.verification == Verification.UNVERIFIED } -> EnrichmentOutcome.VALIDATED
            else -> EnrichmentOutcome.PARTIAL
        }
        return ValidationResult(outcome, fields, relationships, claims, errors)
    }

    /** True when [excerpt] contains one of the words stating [value], as whole words ("api" not in "rapid"). */
    private fun statesValue(excerpt: String, field: String, value: String): Boolean {
        val text = normalize(excerpt)
        return statingWords(field, value).any { Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])").containsMatchIn(text) }
    }

    /** The adapter shows the model "‹ ›" instead of "< >" (UntrustedText); map them back before matching. */
    private fun normalize(s: String) = s.replace('‹', '<').replace('›', '>').lowercase().replace(Regex("\\s+"), " ").trim()
}
