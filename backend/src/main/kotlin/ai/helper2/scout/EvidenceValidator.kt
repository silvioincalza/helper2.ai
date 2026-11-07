package ai.helper2.scout

import ai.helper2.shared.Audience
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.TechLevel
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification

/**
 * Deterministic validation of model output. The guarantee: nothing the model says is published as
 * VERIFIED unless (1) it is in the allowed schema, (2) it cites evidence that was actually
 * retrieved, (3) every cited excerpt appears in that evidence and, for the facts in
 * [MUST_STATE_VALUE], (4) the excerpt states the value itself (or a known synonym). Model
 * confidence is ignored.
 *
 * ADR 0056 adds three rules. Audience and tech level are judgement fields: the model may estimate
 * them without a quote, and they are stored as AI_ESTIMATED, never VERIFIED. A fact that only the
 * product's own pages state is SELF_DECLARED, a relationship they name stays a candidate, and a
 * licence or open-source value the source states wins over them. A summary sentence must keep to
 * its excerpts: every name and number in it is in them, most of its words too, and it holds no
 * superlative, certificate, popularity or comparison.
 *
 * ADR 0066: a field an admin set is not checked at all; the model's value for it is dropped.
 *
 * ADR 0062 adds the type: an article, a news item or a paper keeps only audience, tech level and
 * use case, and names what it is about with ABOUT relationships, at most three ([FieldsByType]).
 */
class EvidenceValidator(
  private val minExcerpt: Int = MIN_EXCERPT_CHARS,
  private val maxExcerpt: Int = MAX_EXCERPT_CHARS,
) {

  companion object {
    /**
     * 20 characters is about three words: enough to state a fact ("Works with Claude Desktop"), too
     * long for a bare token ("MCP", "free") that appears in almost any material and so proves
     * nothing.
     */
    const val MIN_EXCERPT_CHARS = 20
    const val MAX_EXCERPT_CHARS = 300

    /** Summary claims kept per run; the prompt asks for the same number. */
    const val MAX_SUMMARY_CLAIMS = 3

    /**
     * Relationships per run that only the product's own pages name: a "works with" list on a
     * landing page can name every big vendor.
     */
    const val MAX_SELF_DECLARED_RELATIONSHIPS = 3

    /** Fields the model may estimate without a quote; stored as AI_ESTIMATED (ADR 0056). */
    @JvmField val JUDGEMENT_FIELDS = setOf("audience", "techLevel")

    /** ABOUT is only for writing, the others only for the rest ([FieldsByType], ADR 0062). */
    val RELATIONSHIP_KINDS =
      setOf("INTEGRATES_WITH", "USES", "SUPPORTS", "COMPATIBLE_WITH", FieldsByType.ABOUT)
    val USE_CASES =
      setOf(
        "writing",
        "translation",
        "summarization",
        "image-generation",
        "coding",
        "research",
        "transcription",
        "automation",
        "design",
        "learning",
        "data-analysis",
        "chat",
        "video",
        "audio",
      )
    val PLATFORMS =
      setOf(
        "web",
        "desktop",
        "ios",
        "android",
        "cli",
        "api",
        "browser-extension",
        "vscode",
        "node",
        "python",
        "local",
        "remote",
      )

    /**
     * The allowed values, one line per field, for the model's instructions. Built from the same
     * sets the validator checks, so the prompt and the validation cannot drift apart.
     */
    @JvmStatic
    fun allowedValues(): String =
      listOf(
          "type" to ResourceType.entries.map { it.name },
          "audience" to Audience.entries.filter { it != Audience.UNKNOWN }.map { it.name },
          "techLevel" to TechLevel.entries.map { it.name },
          "pricing" to Pricing.entries.map { it.name },
          "openSource" to listOf("true", "false"),
          "license" to listOf("an SPDX licence id, e.g. MIT, Apache-2.0"),
          "useCase" to USE_CASES.toList(),
          "platforms" to PLATFORMS.toList(),
          "relationship kind" to RELATIONSHIP_KINDS.toList(),
        )
        .joinToString("\n") { (field, values) -> "- $field: ${values.joinToString(" | ")}" }

    private val OSI_LICENCE_WORDS =
      listOf("mit", "apache", "bsd", "gpl", "lgpl", "agpl", "mpl", "isc", "unlicense")

    /** Words that state a value of a field whose value must appear in its excerpt. */
    private val SYNONYMS: Map<String, Map<String, List<String>>> =
      mapOf(
        "openSource" to
          mapOf(
            "true" to listOf("open source", "open-source", "opensource") + OSI_LICENCE_WORDS,
            "false" to
              listOf(
                "proprietary",
                "closed source",
                "closed-source",
                "source-available",
                "source available",
              ),
          ),
        "platforms" to
          mapOf(
            "web" to listOf("web", "browser", "online"),
            "desktop" to listOf("desktop", "windows", "macos", "linux"),
            "ios" to listOf("ios", "iphone", "ipad"),
            "android" to listOf("android"),
            "cli" to listOf("cli", "command line", "command-line", "terminal"),
            "api" to listOf("api", "rest", "sdk"),
            "browser-extension" to
              listOf(
                "browser extension",
                "chrome extension",
                "firefox extension",
                "extension",
                "add-on",
              ),
            "vscode" to listOf("vscode", "vs code", "visual studio code"),
            "node" to listOf("node", "node.js", "nodejs", "npm"),
            "python" to listOf("python", "pip", "pypi"),
            "local" to
              listOf("local", "locally", "offline", "self-hosted", "on-device", "on device"),
            "remote" to listOf("remote", "hosted", "cloud"),
          ),
        "type" to
          mapOf(
            "mcp_server" to listOf("mcp server", "mcp", "model context protocol"),
            "agent" to listOf("agents", "agentic", "ai agent"),
            "library" to
              listOf("library", "libraries", "sdk", "package", "framework", "module", "toolkit"),
            "model" to listOf("models", "weights", "checkpoint", "llm"),
            "dataset" to listOf("datasets", "data set", "corpus"),
            "product" to
              listOf(
                "app",
                "application",
                "platform",
                "tool",
                "service",
                "software",
                "assistant",
                "saas",
              ),
            "prompt" to listOf("prompts", "prompt template", "system prompt"),
            "skill" to listOf("skills", "agent skill"),
            "article" to listOf("blog", "blog post", "guide", "tutorial"),
            "paper" to listOf("arxiv", "preprint", "we propose", "we present"),
            "news" to listOf("announced", "announces", "launches", "released"),
            "event" to listOf("conference", "meetup", "summit", "hackathon", "workshop", "webinar"),
            "course" to listOf("courses", "certification", "training", "lessons"),
            // OTHER says nothing: no word states it, so a quote never proves it.
            "other" to listOf(),
          ),
        "useCase" to
          mapOf(
            "writing" to listOf("write", "writes", "writer", "copywriting", "essays", "blog posts"),
            "translation" to listOf("translate", "translates", "translator", "translations"),
            "summarization" to
              listOf("summary", "summaries", "summarize", "summarizes", "summarise", "tl;dr"),
            "image-generation" to
              listOf(
                "image generation",
                "generate images",
                "generates images",
                "text-to-image",
                "image generator",
                "ai images",
              ),
            "coding" to
              listOf("code", "codes", "programming", "developers", "software development", "ide"),
            "transcription" to
              listOf("transcribe", "transcribes", "transcript", "transcripts", "speech-to-text"),
            "automation" to
              listOf("automate", "automates", "automated", "workflow", "workflows", "no-code"),
            "design" to listOf("designs", "designer", "designers", "ui design", "mockups"),
            "learning" to listOf("learn", "learners", "tutor", "tutoring", "students", "course"),
            "data-analysis" to
              listOf("data analysis", "analytics", "analyze data", "analyse data", "spreadsheets"),
            "chat" to listOf("chatbot", "chatbots", "conversation", "conversations", "chat with"),
            "video" to listOf("videos", "video generation", "video editing"),
            "audio" to listOf("voice", "speech", "music", "podcast", "sound"),
            "research" to listOf("researchers", "literature", "papers", "deep research"),
          ),
      )

    /**
     * Facts whose excerpt must itself state the value. Audience and tech level are judgement
     * fields, not facts.
     */
    private val MUST_STATE_VALUE =
      setOf("license", "openSource", "platforms", "pricing", "type", "useCase")

    /**
     * Words that state [value] of [field]: the value itself and its synonyms. A licence is stated
     * by its family name ("Apache-2.0" by "apache", "GPL-3.0" also by "general public license").
     * Pricing is stated by patterns, see [PricingWords].
     */
    internal fun statingWords(field: String, value: String): List<String> {
      val v = value.lowercase()
      if (field == "license") {
        val family =
          v.substringBefore('-').substringBefore('.').trimEnd { it.isDigit() }.ifEmpty { v }
        return listOf(v, family) +
          when (family) {
            "gpl" -> listOf("general public license")
            "lgpl" -> listOf("lesser general public license")
            "agpl" -> listOf("affero general public license")
            "mpl" -> listOf("mozilla public license")
            else -> emptyList()
          }
      }
      if (field == "type" && v == "other") return emptyList()
      val itself = if (field == "type") v.replace('_', ' ') else v
      return listOf(itself) + SYNONYMS[field]?.get(v).orEmpty()
    }
  }

  private val spdx = Regex("^[A-Za-z0-9.+-]{2,40}$")

  private val allowed: Map<String, (String) -> Boolean> =
    mapOf(
      "type" to { v -> ResourceType.entries.any { it.name == v } },
      "audience" to { v -> Audience.entries.any { it.name == v && it != Audience.UNKNOWN } },
      "techLevel" to { v -> TechLevel.entries.any { it.name == v } },
      "pricing" to { v -> Pricing.entries.any { it.name == v } },
      "openSource" to { v -> v == "true" || v == "false" },
      "license" to { v -> spdx.matches(v) },
      "useCase" to { v -> v in USE_CASES },
      "platforms" to { v -> v in PLATFORMS },
    )

  fun validate(input: EnrichmentInput, proposal: EnrichmentProposal): ValidationResult =
    Run(input).validate(proposal)

  /** One validation: the errors it collects and the materials by evidence id. */
  private inner class Run(private val input: EnrichmentInput) {
    val errors = mutableListOf<String>()
    private val byId = input.materials.associateBy { it.evidenceId.toString() }

    fun validate(proposal: EnrichmentProposal): ValidationResult {
      val fields = proposal.fields.map(::field)
      val relationships = capAbout(capSelfDeclared(proposal.relationships.map(::relationship)))
      val claims = proposal.summary.take(MAX_SUMMARY_CLAIMS).mapNotNull(::claim)
      val proposed = proposal.fields.size + proposal.relationships.size + proposal.summary.size
      val accepted =
        fields.count { it.verification == Verification.VERIFIED } +
          relationships.count { it.verification != Verification.REJECTED } +
          claims.size
      val outcome =
        when {
          proposed == 0 -> EnrichmentOutcome.REJECTED.also { errors += "empty proposal" }
          accepted == 0 -> EnrichmentOutcome.REJECTED
          errors.isEmpty() && relationships.none { it.verification == Verification.UNVERIFIED } ->
            EnrichmentOutcome.VALIDATED
          else -> EnrichmentOutcome.PARTIAL
        }
      return ValidationResult(outcome, fields, relationships, claims, errors)
    }

    private fun field(f: ProposedField): CheckedField {
      val label = "field ${f.field.take(40)}"
      val rule = allowed[f.field]
      return when {
        rule == null -> rejected(f, "$label: field not allowed")
        // An admin set this field (ADR 0066): the model's value is not a question to review.
        f.field in input.curatedFields ->
          CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
        !FieldsByType.allowsField(input.type, f.field) ->
          rejected(f, "$label: not a fact of a ${input.type}, but of what it is about")
        !rule(f.value) -> rejected(f, "$label: value not allowed")
        f.field in JUDGEMENT_FIELDS -> estimated(f)
        else -> fact(f, label)
      }
    }

    private fun rejected(f: ProposedField, error: String): CheckedField {
      errors += error
      return CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
    }

    /**
     * A judgement the model may make without a quote: never VERIFIED, always labelled as an
     * estimate. A quote it gives is kept when it checks out, never required.
     */
    private fun estimated(f: ProposedField): CheckedField =
      CheckedField(
        f.field,
        f.value,
        Verification.UNVERIFIED,
        f.citations.mapNotNull { checkOne(it, null) },
        ValueOrigin.AI_ESTIMATED,
      )

    private fun fact(f: ProposedField, label: String): CheckedField {
      val cited =
        check(f.citations, label)
          ?: return CheckedField(f.field, f.value, Verification.REJECTED, emptyList())
      // Self-declared by the quotes that state the value, not by every quote: a neutral record
      // quote
      // next to a vendor quote that states the price does not make the price the source's.
      val stating =
        if (f.field in MUST_STATE_VALUE) cited.filter { statesValue(it.excerpt, f.field, f.value) }
        else cited
      val selfDeclared = stating.ifEmpty { cited }.all { isSelfDeclared(it) }
      val origin = if (selfDeclared) ValueOrigin.SELF_DECLARED else ValueOrigin.AI_EXTRACTED
      val sourceValue = input.sourceFacts[f.field]
      val conflicts = sourceValue != null && !sourceValue.equals(f.value, ignoreCase = true)
      return when {
        conflicts && selfDeclared -> {
          // The source's own metadata (e.g. the licence GitHub states) wins over a vendor's page.
          errors += "$label: the source states another value; the vendor's page does not change it"
          CheckedField(f.field, f.value, Verification.REJECTED, emptyList(), origin)
        }
        conflicts -> {
          errors += "$label: conflicts with source-provided value"
          CheckedField(f.field, f.value, Verification.CONFLICTING, cited, origin)
        }
        f.field in MUST_STATE_VALUE && cited.none { statesValue(it.excerpt, f.field, f.value) } -> {
          errors += "$label: the cited excerpt does not state the value"
          CheckedField(f.field, f.value, Verification.UNVERIFIED, cited, origin)
        }
        else -> CheckedField(f.field, f.value, Verification.VERIFIED, cited, origin)
      }
    }

    private fun relationship(r: ProposedRelationship): CheckedRelationship {
      val label = "relationship ${r.kind.take(20)} ${r.target.take(40)}"
      fun rejected(error: String): CheckedRelationship {
        errors += error
        return CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
      }
      if (r.kind !in RELATIONSHIP_KINDS || r.target.isBlank() || r.target.length > 120) {
        return rejected("$label: invalid kind or target")
      }
      if (!FieldsByType.allowsRelationship(input.type, r.kind)) {
        return rejected("$label: not a relationship of a ${input.type}")
      }
      val cited =
        check(r.citations, label)
          ?: return CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
      val naming = cited.filter { normalize(it.excerpt).contains(normalize(r.target)) }
      if (naming.isEmpty()) {
        return rejected("$label: excerpt does not mention the target")
      }
      val targetId = input.knownResources[r.target.trim().lowercase()]
      // Self-declared by the quotes that name the target: a neutral record quote cited next to
      // them proves nothing about it.
      if (naming.all { isSelfDeclared(it) }) {
        // What a vendor says it works with stays a candidate for a person to confirm.
        return CheckedRelationship(
          r.kind,
          r.target,
          targetId,
          Verification.UNVERIFIED,
          cited,
          ValueOrigin.SELF_DECLARED,
        )
      }
      return CheckedRelationship(
        r.kind,
        r.target,
        targetId,
        if (targetId != null) Verification.VERIFIED else Verification.UNVERIFIED,
        cited,
      )
    }

    /** Keeps the first [MAX_SELF_DECLARED_RELATIONSHIPS] self-declared relationships. */
    private fun capSelfDeclared(all: List<CheckedRelationship>): List<CheckedRelationship> {
      var kept = 0
      return all.map { r ->
        when {
          r.origin != ValueOrigin.SELF_DECLARED || r.verification == Verification.REJECTED -> r
          kept < MAX_SELF_DECLARED_RELATIONSHIPS -> r.also { kept++ }
          else -> {
            errors += "relationship ${r.kind.take(20)} ${r.target.take(40)}: over the cap"
            CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
          }
        }
      }
    }

    /** Keeps the first [FieldsByType.MAX_ABOUT] subjects of a piece of writing. */
    private fun capAbout(all: List<CheckedRelationship>): List<CheckedRelationship> {
      var kept = 0
      return all.map { r ->
        when {
          r.kind != FieldsByType.ABOUT || r.verification == Verification.REJECTED -> r
          kept < FieldsByType.MAX_ABOUT -> r.also { kept++ }
          else -> {
            errors += "relationship ABOUT ${r.target.take(40)}: over the cap"
            CheckedRelationship(r.kind, r.target, null, Verification.REJECTED, emptyList())
          }
        }
      }
    }

    private fun claim(c: ProposedClaim): CheckedClaim? {
      if (c.text.isBlank() || c.text.length > 300) {
        errors += "summary claim: empty or too long"
        return null
      }
      if (c.citations.any { byId[it.evidenceId]?.supportsClaims == false }) {
        errors += "summary claim: cites text that may not support a claim"
        return null
      }
      val cited = check(c.citations, "summary claim") ?: return null
      val problem = ClaimCheck.problem(c.text, cited.map { it.excerpt }, input.resourceTitle)
      if (problem != null) {
        errors += "summary claim: $problem"
        return null
      }
      return CheckedClaim(c.text.trim(), cited, cited.any { isSelfDeclared(it) })
    }

    private fun isSelfDeclared(c: CheckedCitation): Boolean =
      byId[c.evidenceId.toString()]?.selfDeclared == true

    /** Every citation checked, or null (with the error) at the first that fails. */
    private fun check(citations: List<Citation>, what: String): List<CheckedCitation>? {
      if (citations.isEmpty()) {
        errors += "$what: no evidence cited"
        return null
      }
      val out = mutableListOf<CheckedCitation>()
      for (c in citations) {
        out += checkOne(c, what) ?: return null
      }
      return out
    }

    /** The citation when it checks out; else null, with an error when [what] names the owner. */
    private fun checkOne(c: Citation, what: String?): CheckedCitation? {
      val m = byId[c.evidenceId]
      if (m == null) {
        if (what != null) errors += "$what: unknown evidence id ${c.evidenceId.take(40)}"
        return null
      }
      val excerpt = c.excerpt.trim()
      if (excerpt.length !in minExcerpt..maxExcerpt) {
        if (what != null) {
          errors += "$what: excerpt length ${excerpt.length} outside $minExcerpt..$maxExcerpt"
        }
        return null
      }
      if (!normalize(m.text).contains(normalize(excerpt))) {
        if (what != null) errors += "$what: excerpt not found in cited evidence"
        return null
      }
      return if (m.extra)
        CheckedCitation(m.evidenceId, excerpt, m.anchorRecordId, m.sourceUrl, m.retrievedAt)
      else CheckedCitation(m.evidenceId, excerpt)
    }
  }

  private fun statesValue(excerpt: String, field: String, value: String): Boolean =
    EvidenceRules.statesValue(excerpt, field, value)
}

/** The validator's quote rules, for code that checks stored answers again (RecheckEnrichments). */
object EvidenceRules {

  /** The facts whose quote must state the value since ADR 0056 (before: licence, platforms...). */
  @JvmField val NEWLY_STATED = setOf("pricing", "type", "useCase")

  /**
   * True when [excerpt] states [value] of [field]: one of its stating words as a whole word ("api"
   * not in "rapid"), or for pricing one of [PricingWords]' patterns.
   */
  @JvmStatic
  fun statesValue(excerpt: String, field: String, value: String): Boolean {
    val text = normalize(excerpt)
    if (field == "pricing") return PricingWords.states(text, value)
    return EvidenceValidator.statingWords(field, value).any {
      Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])").containsMatchIn(text)
    }
  }

  /** Why a summary sentence fails against its [excerpts], or null ([ClaimCheck]). */
  @JvmStatic
  fun claimProblem(sentence: String, excerpts: List<String>, title: String): String? =
    ClaimCheck.problem(sentence, excerpts, title)
}

/**
 * The adapter shows the model "‹ ›" instead of "< >" (UntrustedText); map them back before
 * matching.
 */
internal fun normalize(s: String) =
  s.replace('‹', '<').replace('›', '>').lowercase().replace(Regex("\\s+"), " ").trim()

/**
 * What states a price model. "Free trial" states nothing: it is removed before matching, so a paid
 * product with a trial is never FREE or FREEMIUM.
 */
internal object PricingWords {

  private val TRIAL =
    Regex(
      "free[- ]trials?|trial for free|try (it )?(for )?free|start (for )?free|" +
        "free for \\d{1,3} days|\\d{1,3}[- ]day free"
    )

  private val PATTERNS: Map<String, List<Regex>> =
    mapOf(
        "FREE" to
          listOf(
            "(?<![a-z0-9])free(?![a-z0-9-])",
            "(?<![a-z0-9])\\$0(?![0-9.,])",
            "no cost",
            "free of charge",
          ),
        "FREEMIUM" to listOf("free plan", "free tier", "free forever", "freemium", "free version"),
        "PAID" to
          listOf(
            "[\$€£]\\s?\\d",
            "\\d\\s?(usd|eur|chf|gbp)(?![a-z])",
            "per month",
            "/ ?mo(?![a-z])",
            "/ ?month",
            "per seat",
            "per user",
            "contact sales",
            "paid plans?",
          ),
        "OPEN_SOURCE_SELF_HOSTED" to
          listOf("open[- ]?source", "self[- ]?host(ed|ing|able)?(?![a-z])"),
      )
      .mapValues { (_, list) -> list.map(::Regex) }

  /** [text] is already [normalize]d. */
  fun states(text: String, value: String): Boolean {
    val withoutTrials = text.replace(TRIAL, " ")
    return PATTERNS[value].orEmpty().any { it.containsMatchIn(withoutTrials) }
  }
}

/**
 * Checks a summary sentence against its excerpts. The model writes the sentence; this keeps it to
 * what the excerpts say. Returns why it fails, or null.
 */
internal object ClaimCheck {

  /** No superlatives, no certificates, no popularity, no comparisons (ADR 0056). */
  private val FORBIDDEN =
    Regex(
      "(?<![a-z0-9])(best|leading|#1|number one|world'?s|trusted by|most popular|" +
        "award[- ]?winning|awards?|soc ?2|hipaa|gdpr|iso ?27001|ce[- ]mark(ed)?|fda|certified|" +
        "compliant|compliance|users|customers|downloads|better than|faster than|cheaper than|" +
        "compared (to|with)|vs\\.?|versus|alternative to)(?![a-z0-9])"
    )

  private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}.+#/-]*")

  private val STOP =
    setOf(
      "this",
      "that",
      "with",
      "from",
      "your",
      "they",
      "their",
      "which",
      "also",
      "have",
      "will",
      "into",
      "more",
      "than",
      "about",
      "what",
      "when",
      "where",
      "while",
      "there",
      "these",
      "those",
      "them",
      "been",
      "were",
      "being",
      "such",
      "each",
      "other",
      "some",
      "only",
      "over",
      "very",
      "just",
      "lets",
      "helps",
      "allows",
      "make",
      "makes",
      "like",
      "well",
      "both",
      "does",
      "provides",
      "offers",
      "includes",
    )

  /** Share of the sentence's content words that must be in its excerpts. */
  private const val MIN_OVERLAP = 0.5

  fun problem(sentence: String, excerpts: List<String>, title: String): String? {
    val lower = normalize(sentence)
    if (FORBIDDEN.containsMatchIn(lower)) {
      return "superlative, certificate, popularity or comparison"
    }
    val source = normalize(excerpts.joinToString(" ") + " " + title)
    // A number must be a whole number of the excerpts ("4" is not in "4000"; "4,000" is "4000").
    val sourceNumbers = numbers(source)
    if (numbers(lower).any { it !in sourceNumbers }) return "names or numbers not in its excerpts"
    val tokens = TOKEN.findAll(sentence).map { it.value.trimEnd('.', ',', '-', '/') }.toList()
    val unsupported =
      tokens.withIndex().firstOrNull { (i, t) ->
        !NUMBER_TOKEN.matches(t) && isNameOrNumber(t, i) && !wholeWordIn(source, t.lowercase())
      }
    if (unsupported != null) return "names or numbers not in its excerpts"
    val words = tokens.map { it.lowercase() }.filter { it.length >= 4 && it.all(Char::isLetter) }
    val content = words.filter { it !in STOP }
    if (content.isEmpty()) return null
    val sourceWords = TOKEN.findAll(source).map { it.value }.filter { it.length >= 4 }.toSet()
    val found = content.count { w -> sourceWords.any { sharesStem(w, it) } }
    return if (found < content.size * MIN_OVERLAP) "too few of its words are in its excerpts"
    else null
  }

  /**
   * A number standing alone: not part of a word ("GPT-4o", "v2") and not part of a longer number.
   * Thousands separators, decimals and a k or m suffix are read as one value.
   */
  private val NUMBER =
    Regex(
      "(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?([km])?(?![\\p{L}\\p{N}]|[.,]\\d)"
    )

  /** A token that is only a number (its parts are checked as numbers, not as names). */
  private val NUMBER_TOKEN = Regex("^[\\d.,]+[kmKM]?$")

  /** The values of the numbers in [text] (lower case): "4,000", "4000" and "4k" are all 4000. */
  private fun numbers(text: String): Set<java.math.BigDecimal> =
    NUMBER.findAll(text)
      .map { m ->
        val whole = m.groupValues[1].replace(",", "")
        val value = java.math.BigDecimal(whole + m.groupValues[2])
        val scaled =
          when (m.groupValues[3]) {
            "k" -> value.multiply(java.math.BigDecimal(1_000))
            "m" -> value.multiply(java.math.BigDecimal(1_000_000))
            else -> value
          }
        scaled.stripTrailingZeros()
      }
      .toSet()

  /** [word] in [text] as a whole word, not inside a longer one ("go" is not in "google"). */
  private fun wholeWordIn(text: String, word: String): Boolean =
    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

  /**
   * A capitalised word after the first, any word with a digit, and a first word with an inner
   * capital ("OpenAI", "GPT-4o").
   */
  private fun isNameOrNumber(token: String, index: Int): Boolean =
    token.any(Char::isDigit) ||
      (index > 0 && token.first().isUpperCase()) ||
      token.drop(1).any(Char::isUpperCase)

  private fun sharesStem(a: String, b: String): Boolean {
    val n = minOf(5, a.length, b.length)
    return a.take(n) == b.take(n)
  }
}
