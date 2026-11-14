package ai.helper2.scout

import ai.helper2.catalog.KnownTarget
import ai.helper2.shared.ResourceType
import ai.helper2.shared.Verification
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceValidatorTest {

  private val evA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
  private val material =
    listOf(
      Material(
        evA,
        URI("https://github.com/a/pg-mcp"),
        Instant.parse("2026-10-22T10:00:00Z"),
        "Postgres MCP server. Works with Claude Desktop and Cursor.  Licensed under the MIT license.",
      )
    )
  private val input =
    EnrichmentInput(
      resourceTitle = "pg-mcp",
      materials = material,
      sourceFacts = mapOf("license" to "MIT"),
      knownResources =
        mapOf("claude desktop" to UUID.fromString("00000000-0000-0000-0000-0000000000cd")),
      knownTargets =
        mapOf(
          "openai" to KnownTarget("OpenAI", KnownTarget.Kind.VENDOR),
          "open ai" to KnownTarget("OpenAI", KnownTarget.Kind.VENDOR),
          "anthropic" to KnownTarget("Anthropic", KnownTarget.Kind.VENDOR),
          "mistral ai" to KnownTarget("Mistral AI", KnownTarget.Kind.VENDOR),
          "slack" to KnownTarget("Slack", KnownTarget.Kind.SERVICE),
        ),
    )
  private val validator = EvidenceValidator()

  private fun field(
    name: String,
    value: String,
    excerpt: String = "Works with Claude Desktop",
    ev: UUID = evA,
  ) = ProposedField(name, value, listOf(Citation(ev.toString(), excerpt)))

  @Test
  fun `supported field with a real excerpt is verified`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = listOf(field("platforms", "desktop", "Works with Claude Desktop and Cursor"))
        ),
      )
    assertEquals(Verification.VERIFIED, r.fields.single().verification)
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }

  @Test
  fun `excerpt not present in retrieved material is rejected as hallucinated`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("pricing", "FREE", "Completely free forever"))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
    assertTrue(r.errors.any { "excerpt not found" in it })
    assertEquals(EnrichmentOutcome.REJECTED, r.outcome)
  }

  @Test
  fun `a quote that is not in the cited material is kept in the error, with the kind of material`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("pricing", "FREE", "Completely free\n  forever"))),
      )
    assertEquals(
      listOf(
        "field pricing: excerpt not found in cited evidence (record): \"Completely free forever\""
      ),
      r.errors,
    )
  }

  @Test
  fun `a quote cited from the wrong material names the material it is in`() {
    val readme =
      Material(
        UUID.fromString("00000000-0000-0000-0000-00000000000b"),
        URI("https://github.com/a/pg-mcp#readme"),
        Instant.parse("2026-10-22T10:00:00Z"),
        "Runs on the desktop with one command.",
        anchorRecordId = evA,
      )
    val r =
      validator.validate(
        input.copy(materials = material + readme),
        EnrichmentProposal(
          fields = listOf(field("platforms", "desktop", "Runs on the desktop with one command."))
        ),
      )
    assertEquals(
      listOf(
        "field platforms: excerpt not found in cited evidence (record), it is in the readme: " +
          "\"Runs on the desktop with one command.\""
      ),
      r.errors,
    )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `citation to an evidence id that was never retrieved is rejected`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("platforms", "desktop", ev = UUID.randomUUID()))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
    assertTrue(r.errors.any { "unknown evidence" in it })
  }

  @Test
  fun `field without any citation is rejected`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(ProposedField("pricing", "FREE", emptyList()))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `fields outside the schema or with disallowed values are rejected`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields =
            listOf(
              field("stars", "99999"),
              field("pricing", "CHEAP"),
            )
        ),
      )
    assertTrue(r.fields.all { it.verification == Verification.REJECTED })
    assertTrue(r.errors.any { "field not allowed" in it })
    assertTrue(r.errors.any { "value not allowed" in it })
  }

  @Test
  fun `dates are never accepted from the model`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("published", "2024-01-01"))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `disagreement with a source provided fact becomes conflicting not overwritten`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = listOf(field("license", "Apache-2.0", "Licensed under the MIT license"))
        ),
      )
    assertEquals(Verification.CONFLICTING, r.fields.single().verification)
  }

  @Test
  fun `relationship to a known resource named in the excerpt is verified`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "COMPATIBLE_WITH",
                "Claude Desktop",
                listOf(Citation(evA.toString(), "Works with Claude Desktop")),
              )
            )
        ),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.VERIFIED, rel.verification)
    assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000000cd"), rel.targetId)
  }

  @Test
  fun `relationship to an unknown target stays an unverified candidate for review`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "INTEGRATES_WITH",
                "Cursor",
                listOf(Citation(evA.toString(), "Claude Desktop and Cursor")),
              )
            )
        ),
      )
    assertEquals(Verification.UNVERIFIED, r.relationships.single().verification)
  }

  @Test
  fun `a target that names a kind of thing, not a product, is not read`() {
    val generic =
      listOf("agent", "Tool", "CLI", "AI model", "LLM", "MCP", "MCP servers", "coding assistant") +
        listOf("Model Context Protocol (MCP) servers", "the Model Context Protocol")
    for (target in generic) {
      val r =
        validator.validate(
          input,
          EnrichmentProposal(
            fields = listOf(field("platforms", "desktop", "Works with Claude Desktop and Cursor")),
            relationships =
              listOf(
                ProposedRelationship(
                  "INTEGRATES_WITH",
                  target,
                  listOf(Citation(evA.toString(), "Postgres MCP server. Works with Claude")),
                )
              ),
          ),
        )
      assertEquals(emptyList(), r.relationships, target)
      assertEquals(emptyList(), r.errors, target)
      assertEquals(EnrichmentOutcome.VALIDATED, r.outcome, target)
    }
  }

  @Test
  fun `a product whose name holds a kind of thing is still a target`() {
    for (target in listOf("Claude Code", "MCP Inspector", "Agent Browser", "Gemini CLI", "llm-d")) {
      assertEquals(false, EvidenceValidator.namesAKindOfThing(target), target)
    }
  }

  private val vendorMaterial =
    listOf(
      Material(
        evA,
        URI("https://github.com/a/pg-mcp"),
        Instant.parse("2026-10-22T10:00:00Z"),
        "Postgres MCP server. Works with OpenAI's models and with Cursor.",
      )
    )

  private fun worksWith(target: String, excerpt: String = "Works with OpenAI's models") =
    ProposedRelationship("INTEGRATES_WITH", target, listOf(Citation(evA.toString(), excerpt)))

  @Test
  fun `a known vendor the excerpt names is a verified target with no resource`() {
    val r =
      validator.validate(
        input.copy(materials = vendorMaterial),
        EnrichmentProposal(relationships = listOf(worksWith("OpenAI's"))),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.VERIFIED, rel.verification)
    assertEquals("OpenAI", rel.target)
    assertEquals(null, rel.targetId)
    assertEquals(KnownTarget.Kind.VENDOR, rel.knownKind)
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }

  @Test
  fun `a known service the excerpt names is a verified target with no resource`() {
    val slack = vendorMaterial.map {
      it.copy(text = "Sends every alert to a Slack channel you pick.")
    }
    val r =
      validator.validate(
        input.copy(materials = slack),
        EnrichmentProposal(
          relationships = listOf(worksWith("slack", "Sends every alert to a Slack channel"))
        ),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.VERIFIED, rel.verification)
    assertEquals("Slack", rel.target)
    assertEquals(null, rel.targetId)
    assertEquals(KnownTarget.Kind.SERVICE, rel.knownKind)
  }

  private fun integratesWithCursor(targetKind: String?) =
    ProposedRelationship(
      "INTEGRATES_WITH",
      "Cursor",
      listOf(Citation(evA.toString(), "Claude Desktop and Cursor")),
      targetKind,
    )

  @Test
  fun `the kind the model states for a target is kept and verifies nothing`() {
    val rel =
      validator
        .validate(
          input,
          EnrichmentProposal(relationships = listOf(integratesWithCursor(" service "))),
        )
        .relationships
        .single()

    assertEquals("SERVICE", rel.statedKind)
    assertEquals(Verification.UNVERIFIED, rel.verification)
    assertEquals(null, rel.knownKind)
  }

  @Test
  fun `a missing or unknown stated kind is a product, and a rejected relationship keeps none`() {
    fun stated(kind: String?) =
      validator
        .validate(input, EnrichmentProposal(relationships = listOf(integratesWithCursor(kind))))
        .relationships
        .single()
        .statedKind

    assertEquals("PRODUCT", stated(null))
    assertEquals("PRODUCT", stated("PLATFORM"))
    assertEquals("VENDOR", stated("vendor"))
    val rejected =
      validator
        .validate(
          input,
          EnrichmentProposal(
            relationships =
              listOf(
                ProposedRelationship(
                  "INTEGRATES_WITH",
                  "Zed",
                  listOf(Citation(evA.toString(), "Claude Desktop and Cursor")),
                  "SERVICE",
                )
              )
          ),
        )
        .relationships
        .single()
    assertEquals(Verification.REJECTED, rejected.verification)
    assertEquals(null, rejected.statedKind)
  }

  @Test
  fun `a name no known target holds is not a vendor, whatever it was once`() {
    val r =
      validator.validate(
        input.copy(materials = vendorMaterial, knownTargets = emptyMap()),
        EnrichmentProposal(relationships = listOf(worksWith("OpenAI"))),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.UNVERIFIED, rel.verification)
    assertEquals(null, rel.knownKind)
  }

  @Test
  fun `a resource can work with several vendors`() {
    val several = vendorMaterial.map {
      it.copy(text = "Works with OpenAI, Anthropic and Mistral AI models.")
    }
    val quote = "Works with OpenAI, Anthropic and Mistral AI"
    val r =
      validator.validate(
        input.copy(materials = several),
        EnrichmentProposal(
          relationships = listOf("OpenAI", "Anthropic", "Mistral AI").map { worksWith(it, quote) }
        ),
      )
    assertEquals(listOf("OpenAI", "Anthropic", "Mistral AI"), r.relationships.map { it.target })
    assertTrue(
      r.relationships.all {
        it.knownKind == KnownTarget.Kind.VENDOR && it.verification == Verification.VERIFIED
      }
    )
    assertEquals(emptyList(), r.errors)
  }

  @Test
  fun `a resource works with several vendors, each one once per kind whatever its spelling`() {
    val text = "Works with OpenAI's models, with Open AI tools and with Anthropic models too."
    val several = listOf(vendorMaterial.single().copy(text = text))
    fun rel(kind: String, target: String, excerpt: String) =
      ProposedRelationship(kind, target, listOf(Citation(evA.toString(), excerpt)))
    val r =
      validator.validate(
        input.copy(materials = several),
        EnrichmentProposal(
          relationships =
            listOf(
              rel("INTEGRATES_WITH", "OpenAI's", "Works with OpenAI's models"),
              rel("INTEGRATES_WITH", "Open AI", "with Open AI tools and with Anthropic"),
              rel("INTEGRATES_WITH", "Anthropic", "with Anthropic models too"),
              rel("SUPPORTS", "OpenAI", "Works with OpenAI's models"),
            )
        ),
      )
    assertEquals(
      listOf(
        "INTEGRATES_WITH" to "OpenAI",
        "INTEGRATES_WITH" to "Anthropic",
        "SUPPORTS" to "OpenAI",
      ),
      r.relationships.map { it.kind to it.target },
    )
    assertTrue(
      r.relationships.all {
        it.knownKind == KnownTarget.Kind.VENDOR && it.verification == Verification.VERIFIED
      }
    )
    assertEquals(emptyList(), r.errors)
  }

  @Test
  fun `a vendor's name is the vendor, even when a package of the catalog has that name`() {
    val openaiPackage = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    val r =
      validator.validate(
        input.copy(materials = vendorMaterial, knownResources = mapOf("openai" to openaiPackage)),
        EnrichmentProposal(relationships = listOf(worksWith("OpenAI"))),
      )
    val rel = r.relationships.single()
    assertEquals(null, rel.targetId)
    assertEquals("OpenAI", rel.target)
    assertEquals(KnownTarget.Kind.VENDOR, rel.knownKind)
    assertEquals(Verification.VERIFIED, rel.verification)
  }

  @Test
  fun `a vendor only its own page names is not linked to a package of that name`() {
    val openaiPackage = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    val own = vendorMaterial.map { it.copy(selfDeclared = true) }
    val r =
      validator.validate(
        input.copy(materials = own, knownResources = mapOf("openai" to openaiPackage)),
        EnrichmentProposal(relationships = listOf(worksWith("OpenAI"))),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.UNVERIFIED, rel.verification)
    assertEquals(null, rel.targetId)
  }

  @Test
  fun `a vendor only the product's own page names still waits for a person`() {
    val own = vendorMaterial.map { it.copy(selfDeclared = true) }
    val r =
      validator.validate(
        input.copy(materials = own),
        EnrichmentProposal(relationships = listOf(worksWith("OpenAI"))),
      )
    val rel = r.relationships.single()
    assertEquals(Verification.UNVERIFIED, rel.verification)
    assertEquals(null, rel.knownKind)
  }

  @Test
  fun `a vendor the excerpt does not name is rejected like any target`() {
    val r =
      validator.validate(
        input.copy(materials = vendorMaterial),
        EnrichmentProposal(
          relationships = listOf(worksWith("Anthropic", "Works with OpenAI's models"))
        ),
      )
    assertEquals(Verification.REJECTED, r.relationships.single().verification)
  }

  @Test
  fun `relationship whose excerpt does not mention the target is rejected`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "USES",
                "LangChain",
                listOf(Citation(evA.toString(), "Works with Claude Desktop")),
              )
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.relationships.single().verification)
  }

  private fun usesPyTorch(text: String, excerpt: String = text) =
    validator.validate(
      input.copy(materials = material.map { it.copy(text = text) }),
      EnrichmentProposal(
        relationships =
          listOf(ProposedRelationship("USES", "PyTorch", listOf(Citation(evA.toString(), excerpt))))
      ),
    )

  @Test
  fun `a target named only as what the resource is a fork of is rejected`() {
    val r = usesPyTorch("A fork of Flavio Schneider's Audio Diffusion - PyTorch")
    assertEquals(Verification.REJECTED, r.relationships.single().verification)
    assertTrue(r.errors.single().contains("fork or an alternative of"))
  }

  @Test
  fun `a target named only as what the resource replaces is rejected`() {
    for (text in
      listOf(
        "An open-source alternative to PyTorch and JAX",
        "Ported from PyTorch to run in the browser",
        "A drop-in replacement for PyTorch DataLoader",
      )) {
      val r = usesPyTorch(text)
      assertEquals(Verification.REJECTED, r.relationships.single().verification, text)
    }
  }

  @Test
  fun `a target named again after the fork clause still counts`() {
    for (text in
      listOf(
        "A fork of Audio Diffusion, rewritten to use PyTorch",
        "A fork of Audio Diffusion. Built on PyTorch 2.",
        "Inspired by nanoGPT; trains models with PyTorch",
      )) {
      val r = usesPyTorch(text)
      assertEquals(Verification.UNVERIFIED, r.relationships.single().verification, text)
    }
  }

  @Test
  fun `VS Code is a product, not a comparison`() {
    val text = "Integrates with VS Code and with JetBrains IDEs."
    val r =
      validator.validate(
        input.copy(materials = material.map { it.copy(text = text) }),
        EnrichmentProposal(
          relationships =
            listOf("VS Code", "JetBrains").map {
              ProposedRelationship("INTEGRATES_WITH", it, listOf(Citation(evA.toString(), text)))
            }
        ),
      )
    assertEquals(
      listOf(Verification.UNVERIFIED, Verification.UNVERIFIED),
      r.relationships.map { it.verification },
    )
  }

  @Test
  fun `a product compared against is not one the resource uses`() {
    val r = usesPyTorch("Twice as fast as training vs. PyTorch on one GPU")
    assertEquals(Verification.REJECTED, r.relationships.single().verification)
  }

  @Test
  fun `summary keeps only claims with valid evidence`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          summary =
            listOf(
              ProposedClaim(
                "An MCP server for Postgres.",
                listOf(Citation(evA.toString(), "Postgres MCP server. Works")),
              ),
              ProposedClaim(
                "Used by Fortune 500 companies.",
                listOf(Citation(evA.toString(), "Fortune 500")),
              ),
            )
        ),
      )
    assertEquals(listOf("An MCP server for Postgres."), r.summary.map { it.text })
    assertEquals(EnrichmentOutcome.PARTIAL, r.outcome)
  }

  @Test
  fun `summary keeps at most three claims, as the prompt asks`() {
    val cite =
      listOf(Citation(evA.toString(), "Postgres MCP server. Works with Claude Desktop and Cursor."))
    val claims =
      listOf(
        "A Postgres MCP server.",
        "Works with Claude Desktop.",
        "Works with Cursor.",
        "An MCP server for Postgres.",
        "Works with Cursor and Claude Desktop.",
      )
    val r =
      validator.validate(
        input,
        EnrichmentProposal(summary = claims.map { ProposedClaim(it, cite) }),
      )
    assertEquals(EvidenceValidator.MAX_SUMMARY_CLAIMS, 3)
    assertEquals(claims.take(3), r.summary.map { it.text })
  }

  @Test
  fun `excerpt matching ignores whitespace and case but not content`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = listOf(field("platforms", "desktop", "works   with claude DESKTOP"))
        ),
      )
    assertEquals(Verification.VERIFIED, r.fields.single().verification)
  }

  @Test
  fun `excessively short excerpts cannot support a claim`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("platforms", "desktop", "MCP"))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `an excerpt under twenty characters cannot support a claim`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("platforms", "desktop", "with Claude Desktop"))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `a language runtime is a platform when the excerpt's own words state it`() {
    fun states(excerpt: String, platform: String) =
      EvidenceRules.statesValue(excerpt, "platforms", platform)
    assertTrue(states("Add the Maven dependency to your pom.xml", "java"))
    assertTrue(states("A Kotlin client for the API", "java"))
    assertTrue(states("Install with go get github.com/acme/agent", "golang"))
    assertTrue(states("A Rust crate, published on crates.io", "rust"))
    assertTrue(states("Targets .NET 8 and ships on NuGet", "dotnet"))
    assertTrue(states("gem install acme-llm", "ruby"))
    assertTrue(
      listOf("java", "golang", "rust", "dotnet", "ruby").all { it in EvidenceValidator.PLATFORMS }
    )
  }

  @Test
  fun `the word go alone, JavaScript and a net domain state no language runtime`() {
    fun states(excerpt: String, platform: String) =
      EvidenceRules.statesValue(excerpt, "platforms", platform)
    assertEquals(false, states("Go to the settings page and paste your key", "golang"))
    assertEquals(false, states("Written in JavaScript for the browser", "java"))
    assertEquals(false, states("The docs are on example.net", "dotnet"))
    assertEquals(false, states("A gem of a tool for writers", "ruby"))
  }

  @Test
  fun `a platform the cited excerpt does not state stays unverified and goes to review`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = listOf(field("platforms", "cli", "Works with Claude Desktop and Cursor"))
        ),
      )
    assertEquals(Verification.UNVERIFIED, r.fields.single().verification)
    assertTrue(r.errors.any { "does not state the value" in it })
  }

  @Test
  fun `a licence and open source are verified only when the excerpt names them`() {
    val noFacts = input.copy(sourceFacts = emptyMap())
    val stated =
      validator.validate(
        noFacts,
        EnrichmentProposal(
          fields =
            listOf(
              field("license", "MIT", "Licensed under the MIT license"),
              field("openSource", "true", "Licensed under the MIT license"),
            )
        ),
      )
    val notStated =
      validator.validate(
        noFacts,
        EnrichmentProposal(
          fields =
            listOf(
              field("license", "Apache-2.0", "Works with Claude Desktop and Cursor"),
              field("openSource", "true", "Works with Claude Desktop and Cursor"),
            )
        ),
      )

    assertEquals(
      listOf(Verification.VERIFIED, Verification.VERIFIED),
      stated.fields.map { it.verification },
    )
    assertEquals(
      listOf(Verification.UNVERIFIED, Verification.UNVERIFIED),
      notStated.fields.map { it.verification },
    )
  }

  @Test
  fun `a licence is stated by its family name and synonyms count as whole words`() {
    assertTrue("apache" in EvidenceValidator.statingWords("license", "Apache-2.0"))
    assertTrue(
      "general public license" in EvidenceValidator.statingWords("license", "GPL-3.0-only")
    )
    val rapid =
      input.copy(
        materials =
          listOf(
            Material(
              evA,
              material[0].sourceUrl,
              material[0].retrievedAt,
              "A rapid prototyping tool for agents",
            )
          )
      )
    val r =
      validator.validate(
        rapid,
        EnrichmentProposal(fields = listOf(field("platforms", "api", "A rapid prototyping tool"))),
      )
    assertEquals(Verification.UNVERIFIED, r.fields.single().verification)
  }

  @Test
  fun `an excerpt quoted with the angle brackets the model saw still matches`() {
    val tagged =
      input.copy(
        materials =
          listOf(
            Material(
              evA,
              material[0].sourceUrl,
              material[0].retrievedAt,
              "Run <cli> commands from any terminal",
            )
          )
      )
    val r =
      validator.validate(
        tagged,
        EnrichmentProposal(
          fields = listOf(field("platforms", "cli", "Run ‹cli› commands from any terminal"))
        ),
      )
    assertEquals(Verification.VERIFIED, r.fields.single().verification)
  }

  @Test
  fun `a type that repeats the catalog's type is not read`() {
    val r =
      validator.validate(
        input.copy(type = ResourceType.MCP_SERVER),
        EnrichmentProposal(
          fields =
            listOf(
              field("type", "MCP_SERVER", "MCP"),
              field("platforms", "desktop", "Works with Claude Desktop and Cursor"),
            )
        ),
      )
    assertEquals(listOf("platforms"), r.fields.map { it.field })
    assertEquals(emptyList(), r.errors)
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }

  @Test
  fun `a type that differs from the catalog's type is still judged`() {
    val r =
      validator.validate(
        input.copy(type = ResourceType.LIBRARY),
        EnrichmentProposal(fields = listOf(field("type", "MCP_SERVER", "Postgres MCP server."))),
      )
    assertEquals(Verification.VERIFIED, r.fields.single().verification)
  }

  @Test
  fun `an answer that only repeats the type is an empty proposal`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(fields = listOf(field("type", "OTHER", "pg-mcp"))),
      )
    assertEquals(EnrichmentOutcome.REJECTED, r.outcome)
    assertEquals(listOf("empty proposal"), r.errors)
  }

  @Test
  fun `use cases over the cap are not read, as the prompt asks`() {
    val useCases = listOf("coding", "automation", "research", "chat", "writing")
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = useCases.map { field("useCase", it, "Works with Claude Desktop and Cursor") }
        ),
      )
    assertEquals(EvidenceValidator.MAX_USE_CASES, 3)
    assertEquals(useCases.take(3), r.fields.map { it.value })
    assertEquals(3, r.errors.size)
  }

  @Test
  fun `a value the quote does not state is recorded but asks nobody to review it`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields = listOf(field("useCase", "coding", "Works with Claude Desktop and Cursor"))
        ),
      )
    assertEquals(Verification.UNVERIFIED, r.fields.single().verification)
    assertEquals(listOf("field useCase: the cited excerpt does not state the value"), r.errors)
    assertEquals(emptyList(), r.forReview)
    assertEquals(EnrichmentOutcome.REJECTED, r.outcome)
  }

  @Test
  fun `another problem in the same answer is still for review, without the unstated values`() {
    val r =
      validator.validate(
        input,
        EnrichmentProposal(
          fields =
            listOf(
              field("useCase", "coding", "Works with Claude Desktop and Cursor"),
              field("pricing", "FREE", "Completely free forever"),
              field("platforms", "desktop", "Works with Claude Desktop and Cursor"),
            )
        ),
      )
    assertEquals(2, r.errors.size)
    assertEquals(
      listOf(
        "field pricing: excerpt not found in cited evidence (record): \"Completely free forever\""
      ),
      r.forReview,
    )
    assertEquals(EnrichmentOutcome.PARTIAL, r.outcome)
  }

  @Test
  fun `a platform or a use case the source already states is not read`() {
    val stated = mapOf("platforms" to setOf("Python"), "useCase" to setOf("coding"))
    val r =
      validator.validate(
        input.copy(sourceStated = stated),
        EnrichmentProposal(
          fields =
            listOf(
              field("platforms", "python", "Postgres MCP server. Works with Claude"),
              field("useCase", "coding", "Postgres MCP server. Works with Claude"),
              field("platforms", "desktop", "Works with Claude Desktop and Cursor"),
            )
        ),
      )
    assertEquals(listOf("desktop"), r.fields.map { it.value })
    assertEquals(emptyList(), r.errors)
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }

  @Test
  fun `a use case the model gives a library is not read`() {
    val r =
      validator.validate(
        input.copy(type = ResourceType.LIBRARY),
        EnrichmentProposal(
          fields =
            listOf(
              field("useCase", "coding", "Works with Claude Desktop and Cursor"),
              field("platforms", "desktop", "Works with Claude Desktop and Cursor"),
            )
        ),
      )
    assertEquals(listOf("platforms"), r.fields.map { it.field })
    assertEquals(emptyList(), r.errors)
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }

  @Test
  fun `empty proposal is rejected not silently validated`() {
    assertEquals(
      EnrichmentOutcome.REJECTED,
      validator.validate(input, EnrichmentProposal()).outcome,
    )
  }
}
