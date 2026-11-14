package ai.helper2.scout

import ai.helper2.catalog.KnownTarget
import ai.helper2.catalog.ScoutPolicy
import ai.helper2.shared.Audience
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.TechLevel
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification

class EvidenceValidator(
  private val minExcerpt: Int = MIN_EXCERPT_CHARS,
  private val maxExcerpt: Int = MAX_EXCERPT_CHARS,
) {

  companion object {
    const val MIN_EXCERPT_CHARS = 20
    const val MAX_EXCERPT_CHARS = ScoutPolicy.QUOTE_CHARS

    const val MAX_SUMMARY_CLAIMS = 3

    const val MAX_USE_CASES = 3

    private const val USE_CASE = "useCase"

    const val MAX_SELF_DECLARED_RELATIONSHIPS = 3

    @JvmField val JUDGEMENT_FIELDS = setOf("audience", "techLevel")

    val RELATIONSHIP_KINDS = (FieldsByType.KINDS + FieldsByType.ABOUT).toSet()

    const val STATED_PRODUCT = "PRODUCT"

    val STATED_KINDS = setOf(STATED_PRODUCT, "VENDOR", "SERVICE")

    fun statedKindOf(answer: String?): String =
      answer?.trim()?.uppercase()?.takeIf { it in STATED_KINDS } ?: STATED_PRODUCT

    private val KIND_OF_THING =
      Regex(
        "(the |an? )?(ai |llm |coding )?" +
          "(agents?|tools?|clis?|models?|llms?|skills?|plugins?|apis?|sdks?|apps?|assistants?|" +
          "(model context protocol|mcp)( \\(mcp\\))?( servers?| clients?)?)"
      )

    internal fun namesAKindOfThing(target: String): Boolean =
      KIND_OF_THING.matches(target.trim().lowercase())

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
        "java",
        "golang",
        "rust",
        "dotnet",
        "ruby",
        "local",
        "remote",
      )

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
            "java" to listOf("java", "jvm", "kotlin", "scala", "maven", "gradle"),
            "golang" to
              listOf(
                "golang",
                "go get",
                "go install",
                "go mod",
                "go module",
                "go package",
                "go library",
                "go sdk",
                "go client",
                "written in go",
              ),
            "rust" to listOf("rust", "cargo", "crates.io"),
            "dotnet" to listOf("dotnet", ".net", "c#", "csharp", "nuget"),
            "ruby" to listOf("ruby", "rubygems", "gem install"),
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

    private val MUST_STATE_VALUE =
      setOf("license", "openSource", "platforms", "pricing", "type", "useCase")

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

  private inner class Run(private val input: EnrichmentInput) {
    val errors = mutableListOf<String>()
    private val unstated = mutableSetOf<String>()
    private val byId = input.materials.associateBy { it.evidenceId.toString() }

    fun validate(proposal: EnrichmentProposal): ValidationResult {
      val read =
        withinTheUseCaseCap(
          proposal.fields
            .filterNot(::repeatsTheType)
            .filterNot(::repeatsTheSource)
            .filterNot(::isAUseCaseTheTypeDoesNotRead)
        )
      val fields = read.map(::field)
      val named = proposal.relationships.filterNot { namesAKindOfThing(it.target) }
      val relationships =
        capAbout(capSelfDeclared(oncePerKnownTarget(named.map(::relationshipWithStatedKind))))
      val claims = proposal.summary.take(MAX_SUMMARY_CLAIMS).mapNotNull(::claim)
      val proposed = read.size + named.size + proposal.summary.size
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
      return ValidationResult(outcome, fields, relationships, claims, errors, errors - unstated)
    }

    private fun repeatsTheType(f: ProposedField): Boolean =
      f.field == FieldsByType.TYPE && f.value == input.type.name

    private fun isAUseCaseTheTypeDoesNotRead(f: ProposedField): Boolean =
      f.field == USE_CASE && !FieldsByType.readsUseCase(input.type)

    private fun repeatsTheSource(f: ProposedField): Boolean =
      input.sourceStated[f.field].orEmpty().any { it.equals(f.value, ignoreCase = true) }

    private fun withinTheUseCaseCap(fields: List<ProposedField>): List<ProposedField> {
      var useCases = 0
      return fields.filter { it.field != USE_CASE || useCases++ < MAX_USE_CASES }
    }

    private fun field(f: ProposedField): CheckedField {
      val label = "field ${f.field.take(40)}"
      val rule = allowed[f.field]
      return when {
        rule == null -> rejected(f, "$label: field not allowed")
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
      val stating =
        if (f.field in MUST_STATE_VALUE) cited.filter { statesValue(it.excerpt, f.field, f.value) }
        else cited
      val selfDeclared = stating.ifEmpty { cited }.all { isSelfDeclared(it) }
      val origin = if (selfDeclared) ValueOrigin.SELF_DECLARED else ValueOrigin.AI_EXTRACTED
      val sourceValue = input.sourceFacts[f.field]
      val conflicts = sourceValue != null && !sourceValue.equals(f.value, ignoreCase = true)
      return when {
        conflicts && selfDeclared -> {
          errors += "$label: the source states another value; the vendor's page does not change it"
          CheckedField(f.field, f.value, Verification.REJECTED, emptyList(), origin)
        }
        conflicts -> {
          errors += "$label: conflicts with source-provided value"
          CheckedField(f.field, f.value, Verification.CONFLICTING, cited, origin)
        }
        f.field in MUST_STATE_VALUE && cited.none { statesValue(it.excerpt, f.field, f.value) } -> {
          val error = "$label: ${ScoutPolicy.UNSTATED_VALUE}"
          errors += error
          unstated += error
          CheckedField(f.field, f.value, Verification.UNVERIFIED, cited, origin)
        }
        else -> CheckedField(f.field, f.value, Verification.VERIFIED, cited, origin)
      }
    }

    private fun relationshipWithStatedKind(r: ProposedRelationship): CheckedRelationship {
      val checked = relationship(r)
      return if (checked.verification == Verification.REJECTED) checked
      else checked.copy(statedKind = statedKindOf(r.targetKind))
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
      val mentioning = cited.filter { normalize(it.excerpt).contains(normalize(r.target)) }
      if (mentioning.isEmpty()) {
        return rejected("$label: excerpt does not mention the target")
      }
      val naming =
        if (r.kind == FieldsByType.ABOUT) mentioning
        else mentioning.filter { Lineage.namesBeyond(it.excerpt, r.target) }
      if (naming.isEmpty()) {
        return rejected(
          "$label: excerpt names the target only as what the resource is a fork or an alternative of"
        )
      }
      val known = input.knownTargets[KnownTarget.key(r.target)]
      val targetId = if (known == null) input.knownResources[r.target.trim().lowercase()] else null
      if (naming.all { isSelfDeclared(it) }) {
        return CheckedRelationship(
          r.kind,
          r.target,
          targetId,
          Verification.UNVERIFIED,
          cited,
          ValueOrigin.SELF_DECLARED,
        )
      }
      if (known != null) {
        return CheckedRelationship(
          r.kind,
          known.name,
          null,
          Verification.VERIFIED,
          cited,
          knownKind = known.kind,
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

    private fun oncePerKnownTarget(all: List<CheckedRelationship>): List<CheckedRelationship> {
      val seen = mutableSetOf<Pair<String, String>>()
      return all.filter { it.knownKind == null || seen.add(it.kind to it.target) }
    }

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
      if (!holds(m, excerpt)) {
        if (what != null) errors += "$what: ${notFound(m, excerpt)}"
        return null
      }
      return if (m.extra)
        CheckedCitation(m.evidenceId, excerpt, m.anchorRecordId, m.sourceUrl, m.retrievedAt)
      else CheckedCitation(m.evidenceId, excerpt)
    }

    private fun holds(m: Material, excerpt: String): Boolean =
      normalize(m.text).contains(normalize(excerpt))

    private fun notFound(cited: Material, excerpt: String): String {
      val elsewhere = input.materials.firstOrNull { it !== cited && holds(it, excerpt) }
      val where = if (elsewhere == null) "" else ", it is in the ${elsewhere.kind}"
      val quote = excerpt.replace(Regex("\\s+"), " ")
      return "excerpt not found in cited evidence (${cited.kind})$where: \"$quote\""
    }
  }

  private fun statesValue(excerpt: String, field: String, value: String): Boolean =
    EvidenceRules.statesValue(excerpt, field, value)
}

object EvidenceRules {
  @JvmField val NEWLY_STATED = setOf("pricing", "type", "useCase")

  @JvmStatic
  fun statesValue(excerpt: String, field: String, value: String): Boolean {
    val text = normalize(excerpt)
    if (field == "pricing") return PricingWords.states(text, value)
    return EvidenceValidator.statingWords(field, value).any {
      Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])").containsMatchIn(text)
    }
  }

  @JvmStatic
  fun claimProblem(sentence: String, excerpts: List<String>, title: String): String? =
    ClaimCheck.problem(sentence, excerpts, title)
}

internal fun normalize(s: String) =
  s.replace('‹', '<').replace('›', '>').lowercase().replace(Regex("\\s+"), " ").trim()

internal object Lineage {

  private val CLAUSE =
    Regex(
      "(?<![a-z0-9])(fork(ed)? (of|from)|port(ed)? (of|from)|clone of|alternative to|" +
        "replacement for|inspired by|instead of|unlike|compared (to|with)|vs\\.?(?! ?code(?![a-z0-9]))|versus)" +
        "(?![a-z0-9])([^,;.]|\\.(?=\\S))*"
    )

  fun namesBeyond(excerpt: String, target: String): Boolean =
    normalize(excerpt).replace(CLAUSE, " ").contains(normalize(target))
}

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

  fun states(text: String, value: String): Boolean {
    val withoutTrials = text.replace(TRIAL, " ")
    return PATTERNS[value].orEmpty().any { it.containsMatchIn(withoutTrials) }
  }
}

internal object ClaimCheck {
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

  private const val MIN_OVERLAP = 0.5

  fun problem(sentence: String, excerpts: List<String>, title: String): String? {
    val lower = normalize(sentence)
    if (FORBIDDEN.containsMatchIn(lower)) {
      return "superlative, certificate, popularity or comparison"
    }
    val source = normalize(excerpts.joinToString(" ") + " " + title)
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

  private val NUMBER =
    Regex(
      "(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?([km])?(?![\\p{L}\\p{N}]|[.,]\\d)"
    )

  private val NUMBER_TOKEN = Regex("^[\\d.,]+[kmKM]?$")

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

  private fun wholeWordIn(text: String, word: String): Boolean =
    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

  private fun isNameOrNumber(token: String, index: Int): Boolean =
    token.any(Char::isDigit) ||
      (index > 0 && token.first().isUpperCase()) ||
      token.drop(1).any(Char::isUpperCase)

  private fun sharesStem(a: String, b: String): Boolean {
    val n = minOf(5, a.length, b.length)
    return a.take(n) == b.take(n)
  }
}
