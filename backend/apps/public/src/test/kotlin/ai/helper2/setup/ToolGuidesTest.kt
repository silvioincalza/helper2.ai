package ai.helper2.setup

import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.quota.ClientBudgets
import ai.helper2.platform.quota.ClientDayQuota
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.platform.resilience.Resilience4jPolicies
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolGuidesTest {

  private val clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
  private val doc =
    Material(
      "readme",
      MaterialKind.README,
      URI("https://github.com/acme/x"),
      clock.instant(),
      "Install acme-x from npm with this one command:\n\n```bash\nnpm install acme-x\n```\n",
    )
  private val ref =
    ToolRef.of("acme-x", "LIBRARY", listOf("https://github.com/acme/x"), emptyList())

  private class Model(
    val confirms: (List<StepClaim>) -> Set<String> = { steps -> steps.map { it.key }.toSet() },
    val reply: () -> ProposedGuide,
  ) : ToolGuideModel {
    var calls = 0
    val checked = mutableListOf<List<StepClaim>>()
    override val model = "fake"
    override val configFingerprint = "fp"

    override fun write(ref: ToolRef, materials: List<Material>): ProposedGuide {
      calls++
      return reply()
    }

    override fun confirm(ref: ToolRef, steps: List<StepClaim>): Set<String> {
      checked += steps
      return confirms(steps)
    }
  }

  private class MemoryCache : ToolGuideCache {
    val map = mutableMapOf<String, ToolGuideAnswer>()
    val ttls = mutableMapOf<String, Duration>()

    override fun get(key: String) = map[key]

    override fun put(key: String, answer: ToolGuideAnswer, ttl: Duration) {
      map[key] = answer
      ttls[key] = ttl
    }
  }

  private val ttls = ToolGuideTtls(answer = Duration.ofDays(7), unreadable = Duration.ofMinutes(10))

  private fun guides(
    model: ToolGuideModel?,
    cache: ToolGuideCache = MemoryCache(),
    materials: ToolMaterials = ToolMaterials { listOf(doc) },
    stepText: StepTextMode = StepTextMode.VERIFIED,
  ) = ToolGuides(materials, model, cache, clock, ttls, stepText = stepText)

  private fun failing(reason: ToolGuideUnavailable.Reason) = Model {
    throw ToolGuideUnavailable(reason)
  }

  private val good =
    ProposedGuide(
      listOf(
        ProposedSection(
          "INSTALL",
          listOf(
            ProposedStep(
              "Install it.",
              "readme",
              "Install acme-x from npm with this one command:",
              "npm install acme-x",
            )
          ),
        )
      )
    )

  private val keys = QuotaKeys("test-secret", clock)

  private fun perClient(guides: Int, network: Int = 100) =
    ClientDayQuota(
      ClientBudgets("setup-client:", guides, clock, keys, CallCounter.NONE, 100),
      ClientBudgets("setup-network:", network, clock, keys, CallCounter.NONE, 100),
    )

  private fun bulkhead(slots: Int) =
    Resilience4jPolicies(
      CircuitBreakerRegistry.ofDefaults(),
      BulkheadRegistry.of(
          BulkheadConfig.custom().maxConcurrentCalls(slots).maxWaitDuration(Duration.ZERO).build()
        )
        .also { it.bulkhead(ToolGuides.WRITER_SLOTS) },
      RetryRegistry.ofDefaults(),
    )

  @Test
  fun `a visitor past its daily guides gets no new one, but a written one still opens`() {
    val cache = MemoryCache()
    val clients = perClient(1)
    val first = ToolGuides({ listOf(doc) }, Model { good }, cache, clock, ttls, visitors = clients)
    first.answer("written", ref, "203.0.113.7")

    val refused = first.answer("new", ref, "203.0.113.7")

    assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, refused.status)
    assertEquals("quota", refused.unavailable)
    assertEquals(
      Instant.parse("2026-10-29T00:00:00Z"),
      refused.retryAt,
      "back at the next 00:00 UTC",
    )
    assertEquals(1, cache.map.size, "the refusal is not kept for others")
    assertEquals(ToolGuideStatus.OK, first.answer("written", ref, "203.0.113.7").status)
    assertEquals(ToolGuideStatus.OK, first.answer("new", ref, "198.51.100.1").status)
  }

  @Test
  fun `visitors of one network share its daily guides`() {
    val guides =
      ToolGuides(
        { listOf(doc) },
        Model { good },
        MemoryCache(),
        clock,
        ttls,
        visitors = perClient(guides = 20, network = 1),
      )
    guides.answer("one", ref, "198.51.100.7")

    assertEquals("quota", guides.answer("two", ref, "198.51.100.8").unavailable)
    assertEquals(ToolGuideStatus.OK, guides.answer("two", ref, "203.0.113.9").status)
  }

  @Test
  fun `a guide the model could not write gives the visitor's turn back`() {
    val clients = perClient(1)
    val guides =
      ToolGuides(
        { listOf(doc) },
        failing(ToolGuideUnavailable.Reason.PROVIDER),
        MemoryCache(),
        clock,
        ttls,
        visitors = clients,
      )
    guides.answer("id", ref, "203.0.113.7")

    assertEquals(1, clients.left("203.0.113.7"))
  }

  private fun counted(visitors: ClientDayQuota, model: ToolGuideModel? = Model { good }) =
    ToolGuides({ listOf(doc) }, model, MemoryCache(), clock, ttls, visitors = visitors)

  @Test
  fun `guides left is read without taking a turn, and only a new guide takes one`() {
    val guides = counted(perClient(2))
    assertEquals(SetupGuidesLeft(2, 2), guides.guidesLeft("203.0.113.7"))

    guides.answer("written", ref, "203.0.113.7")

    assertEquals(SetupGuidesLeft(2, 1), guides.guidesLeft("203.0.113.7"))
    assertEquals(SetupGuidesLeft(2, 1), guides.guidesLeft("203.0.113.7"), "reading is free")
    guides.answer("written", ref, "203.0.113.7")
    assertEquals(SetupGuidesLeft(2, 1), guides.guidesLeft("203.0.113.7"), "a written guide")
    assertEquals(SetupGuidesLeft(2, 2), guides.guidesLeft("192.0.2.1"), "another visitor")
  }

  @Test
  fun `guides left is the lower of the visitor's and its network's, under the visitor's limit`() {
    val guides = counted(perClient(guides = 20, network = 1))
    guides.answer("one", ref, "198.51.100.7")

    assertEquals(SetupGuidesLeft(20, 0), guides.guidesLeft("198.51.100.8"), "the same /24")
    assertEquals(SetupGuidesLeft(20, 1), guides.guidesLeft("203.0.113.9"), "another network")
  }

  @Test
  fun `a guide the model could not write leaves the guides left as they were`() {
    val guides = counted(perClient(1), failing(ToolGuideUnavailable.Reason.PROVIDER))
    guides.answer("id", ref, "203.0.113.7")

    assertEquals(SetupGuidesLeft(1, 1), guides.guidesLeft("203.0.113.7"))
  }

  @Test
  fun `past the limit no guide is left, and a limit of zero leaves none`() {
    val guides = counted(perClient(1))
    guides.answer("one", ref, "203.0.113.7")
    guides.answer("two", ref, "203.0.113.7")

    assertEquals(SetupGuidesLeft(1, 0), guides.guidesLeft("203.0.113.7"))
    assertEquals(SetupGuidesLeft(0, 0), counted(perClient(0)).guidesLeft("203.0.113.7"))
  }

  @Test
  fun `without a model, or without a visitor quota, no guides left are said`() {
    assertNull(counted(perClient(2), model = null).guidesLeft("203.0.113.7"), "nothing is spent")
    assertNull(guides(Model { good }).guidesLeft("203.0.113.7"), "nothing is counted")
  }

  @Test
  fun `when every writer slot is taken the guide says busy, and the same guide asked twice is written once`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val model = Model {
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      good
    }
    val guides =
      ToolGuides({ listOf(doc) }, model, MemoryCache(), clock, ttls, resilience = bulkhead(1))
    val threads = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val writing = threads.submit<ToolGuideAnswer> { guides.answer("id", ref, "a") }
      val same =
        threads.submit<ToolGuideAnswer> {
          entered.await(5, TimeUnit.SECONDS)
          guides.answer("id", ref, "b")
        }
      entered.await(5, TimeUnit.SECONDS)
      val other = guides.answer("other", ref, "c")
      release.countDown()

      assertEquals("busy", other.unavailable)
      assertEquals(ToolGuideStatus.OK, writing.get(5, TimeUnit.SECONDS).status)
      assertEquals(ToolGuideStatus.OK, same.get(5, TimeUnit.SECONDS).status)
      assertEquals(1, model.calls)
    } finally {
      threads.shutdownNow()
    }
  }

  @Test
  fun `answers OK once, then from the cache`() {
    val model = Model { good }
    val cache = MemoryCache()
    val guides = guides(model, cache)
    val a = guides.answer("id", ref)
    assertEquals(Duration.ofDays(7), cache.ttls.values.single())
    assertEquals(ToolGuideStatus.OK, a.status)
    assertEquals(a, guides.answer("id", ref))
    assertEquals(1, model.calls)
  }

  private val twoSteps =
    ProposedGuide(
      listOf(
        ProposedSection(
          "INSTALL",
          listOf(
            ProposedStep(
              "Install it with npm.",
              "readme",
              "Install acme-x from npm with this one command:",
              "npm install acme-x",
            )
          ),
        ),
        ProposedSection(
          "USE",
          listOf(
            ProposedStep(
              "Start it on port 9000 with sudo.",
              "readme",
              "Run it as a server on your machine with this command:",
              "acme-x serve",
            )
          ),
        ),
      )
    )

  private val twoDocs = ToolMaterials {
    listOf(
      doc.copy(
        text =
          doc.text +
            "\nRun it as a server on your machine with this command:\n\n```bash\nacme-x serve\n```\n"
      )
    )
  }

  @Test
  fun `given a step text the check confirms, the visitor sees the text with its quote and command`() {
    val model = Model { good }

    val step = guides(model).answer("id", ref).sections.single().steps.single()

    assertEquals("Install it.", step.text)
    assertEquals("npm install acme-x", step.code!!.text)
    assertEquals(
      listOf(
        StepClaim(
          "s1",
          "Install it.",
          "Install acme-x from npm with this one command:",
          "npm install acme-x",
        )
      ),
      model.checked.single(),
    )
  }

  @Test
  fun `given a step text the check does not confirm, the visitor sees only its quote and command`() {
    val model = Model(confirms = { setOf("s1") }) { twoSteps }

    val answer = guides(model, materials = twoDocs).answer("id", ref)

    val (install, use) = answer.sections.map { it.steps.single() }
    assertEquals("Install it with npm.", install.text)
    assertNull(use.text, "the unconfirmed AI text is neither shown nor kept")
    assertEquals("Run it as a server on your machine with this command:", use.quote)
    assertEquals("acme-x serve", use.code!!.text)
    assertEquals(ToolGuideStatus.OK, answer.status)
  }

  @Test
  fun `given a check that gives no verdict, every step shows only its source and the guide still opens`() {
    val model = Model(confirms = { emptySet() }) { twoSteps }

    val answer = guides(model, materials = twoDocs).answer("id", ref)

    assertEquals(ToolGuideStatus.OK, answer.status)
    assertEquals(listOf(null, null), answer.sections.map { it.steps.single().text })
    assertEquals(1, model.checked.size, "one check call for all steps")
  }

  @Test
  fun `given a check that could not run, the guide opens from the source and is not kept`() {
    for (reason in ToolGuideUnavailable.Reason.entries) {
      val model = Model(confirms = { throw ToolGuideUnavailable(reason) }) { twoSteps }
      val cache = MemoryCache()
      val guides = guides(model, cache, twoDocs)

      val answer = guides.answer("id", ref)
      guides.answer("id", ref)

      assertEquals(ToolGuideStatus.OK, answer.status, reason.name)
      assertEquals(listOf(null, null), answer.sections.map { it.steps.single().text }, reason.name)
      assertEquals(emptyMap(), cache.map, reason.name)
      assertEquals(2, model.calls, "the next visit writes and checks again: ${reason.name}")
    }
  }

  @Test
  fun `given a check that runs after one that could not, the checked guide is kept`() {
    var checks = 0
    val failsOnce: (List<StepClaim>) -> Set<String> = { steps ->
      if (++checks == 1) throw ToolGuideUnavailable(ToolGuideUnavailable.Reason.PROVIDER)
      steps.map { it.key }.toSet()
    }
    val model = Model(failsOnce) { twoSteps }
    val cache = MemoryCache()
    val guides = guides(model, cache, twoDocs)
    guides.answer("id", ref)

    val checked = guides.answer("id", ref)
    val again = guides.answer("id", ref)

    assertEquals(listOf(false, false), checked.sections.map { it.steps.single().text == null })
    assertEquals(checked, again)
    assertEquals(2, model.calls, "the kept guide costs no third model call")
    assertEquals(Duration.ofDays(7), cache.ttls.values.single())
  }

  @Test
  fun `in source-only mode, no check call is made and every step shows only its quote and command`() {
    val model = Model { twoSteps }

    val answer =
      guides(model, materials = twoDocs, stepText = StepTextMode.SOURCE_ONLY).answer("id", ref)

    assertEquals(ToolGuideStatus.OK, answer.status)
    assertEquals(listOf(null, null), answer.sections.map { it.steps.single().text })
    assertEquals(
      listOf("npm install acme-x", "acme-x serve"),
      answer.sections.map { it.steps.single().code!!.text },
    )
    assertEquals(1, model.calls, "only the call that writes the guide")
    assertEquals(emptyList(), model.checked, "zero check calls")
  }

  @Test
  fun `the two modes keep their guides apart, so switching never serves the other mode's guide`() {
    val cache = MemoryCache()
    val model = Model { good }

    val verified = guides(model, cache).answer("id", ref)
    val sourceOnly = guides(model, cache, stepText = StepTextMode.SOURCE_ONLY).answer("id", ref)

    assertEquals("Install it.", verified.sections.single().steps.single().text)
    assertNull(sourceOnly.sections.single().steps.single().text)
    assertEquals(2, cache.map.size)
    assertEquals(2, model.calls)
  }

  @Test
  fun `no step passed the code checks, so no check call is made`() {
    val model = Model { ProposedGuide(emptyList()) }

    assertEquals(ToolGuideStatus.NO_STEPS, guides(model).answer("id", ref).status)
    assertEquals(emptyList(), model.checked)
  }

  @Test
  fun `the kept answer holds the checked steps and the document links, not the document text`() {
    val cache = MemoryCache()
    val guides = guides(Model { good }, cache)
    guides.answer("id", ref)
    val kept = cache.map.values.single()
    assertEquals(
      listOf(
        MaterialRef(
          "readme",
          MaterialKind.README,
          URI("https://github.com/acme/x"),
          clock.instant(),
        )
      ),
      kept.materials,
    )
    assertEquals("npm install acme-x", kept.sections.single().steps.single().code!!.text)
  }

  @Test
  fun `a section the guide does not have is rejected, never shown`() {
    val answer =
      ProposedGuide(
        listOf(
          ProposedSection(
            "ANSWER",
            listOf(
              ProposedStep(
                "Install it.",
                "readme",
                "Install acme-x from npm with this one command:",
                "npm install acme-x",
              )
            ),
          )
        )
      )
    val a = guides(Model { answer }).answer("id", ref)
    assertEquals(ToolGuideStatus.NO_STEPS, a.status)
    assertEquals(mapOf("SECTION" to 1), a.rejectedBy)
  }

  @Test
  fun `no documents, no model, budget and nothing kept are told apart`() {
    assertEquals(
      ToolGuideStatus.NO_MATERIAL,
      guides(Model { good }, MemoryCache(), { emptyList() }).answer("id", ref).status,
    )
    val noRef = ToolRef.of("x", "PRODUCT", listOf("https://example.com"), emptyList())
    assertEquals(
      ToolGuideStatus.NO_MATERIAL,
      guides(null, MemoryCache(), { error("not called") }).answer("id", noRef).status,
    )
    val none = guides(null, MemoryCache()).answer("id", ref)
    assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, none.status)
    val budget = guides(failing(ToolGuideUnavailable.Reason.BUDGET)).answer("id", ref)
    assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, budget.status)
    assertEquals("budget", budget.unavailable)
    assertEquals(Instant.parse("2026-10-29T00:00:00Z"), budget.retryAt)
    assertNull(guides(failing(ToolGuideUnavailable.Reason.PROVIDER)).answer("id", ref).retryAt)
    assertNull(none.retryAt)
    val invented =
      ProposedGuide(
        listOf(
          ProposedSection(
            "INSTALL",
            listOf(ProposedStep("Install.", "readme", "Install with pip", "pip install acme-x")),
          )
        )
      )
    val empty = guides(Model { invented }).answer("id", ref)
    assertEquals(ToolGuideStatus.NO_STEPS, empty.status)
    assertEquals(1, empty.rejected)
  }

  @Test
  fun `an unreadable model answer is kept briefly, so asking again soon costs no model call`() {
    val model = failing(ToolGuideUnavailable.Reason.UNREADABLE)
    val cache = MemoryCache()
    val guides = guides(model, cache)
    val first = guides.answer("id", ref)
    val again = guides.answer("id", ref)
    assertEquals(1, model.calls)
    assertEquals(ToolGuideStatus.LLM_UNAVAILABLE, first.status)
    assertEquals("error", first.unavailable)
    assertEquals(first, again)
    assertEquals(Duration.ofMinutes(10), cache.ttls.values.single())
  }

  @Test
  fun `a provider error and a spent budget are not kept, they recover by themselves`() {
    for (reason in
      listOf(ToolGuideUnavailable.Reason.PROVIDER, ToolGuideUnavailable.Reason.BUDGET)) {
      val model = failing(reason)
      val cache = MemoryCache()
      val guides = guides(model, cache)
      guides.answer("id", ref)
      val again = guides.answer("id", ref)
      assertEquals(2, model.calls, reason.name)
      assertEquals(reason.shown, again.unavailable)
      assertEquals(emptyMap(), cache.map)
    }
  }

  @Test
  fun `tool ref keeps identifiers only, from known hosts`() {
    val r =
      ToolRef.of(
        "x",
        "MCP_SERVER",
        listOf(
          "https://evil.example/a/b",
          "https://github.com/acme/pg-mcp.git",
          "https://huggingface.co/spaces/a/b",
        ),
        listOf("@acme/pg-mcp", "../etc/passwd"),
      )
    assertEquals("acme/pg-mcp", r.githubRepo)
    assertEquals("@acme/pg-mcp", r.npmPackage)
    assertEquals("a/b", r.hfSpace)
    assertNull(r.hfModel)
    assertEquals(
      "org/model",
      ToolRef.of("m", "MODEL", listOf("https://huggingface.co/org/model"), emptyList()).hfModel,
    )
    assertNull(
      ToolRef.of("t", "LIBRARY", listOf("https://github.com/topics/mcp"), emptyList()).githubRepo
    )
  }

  @Test
  fun `dot segments and names outside the GitHub and Hugging Face rules never become a document path`() {
    fun repo(url: String) = ToolRef.of("t", "LIBRARY", listOf(url), emptyList()).githubRepo
    fun hf(url: String) = ToolRef.of("t", "MODEL", listOf(url), emptyList())
    assertNull(repo("https://github.com/../orgs"))
    assertNull(repo("https://github.com/acme/.."))
    assertNull(repo("https://github.com/acme/."))
    assertNull(repo("https://github.com/-acme/x"))
    assertNull(repo("https://github.com/ac--me/x"))
    assertNull(repo("https://github.com/acme_co/x"))
    assertNull(repo("https://github.com/" + "a".repeat(40) + "/x"))
    assertEquals("acme-co/x.y_z", repo("https://github.com/acme-co/x.y_z"))
    assertEquals("acme/.github", repo("https://github.com/acme/.github"))
    assertNull(hf("https://huggingface.co/../model").hfModel)
    assertNull(hf("https://huggingface.co/org/..").hfModel)
    assertNull(hf("https://huggingface.co/spaces/org/..").hfSpace)
    assertNull(hf("https://huggingface.co/org/-model").hfModel)
    assertNull(hf("https://huggingface.co/org/a..b").hfModel)
    assertEquals("org/Model-7B.v1", hf("https://huggingface.co/org/Model-7B.v1").hfModel)
  }
}
