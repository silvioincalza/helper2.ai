package ai.helper2.guide.web

import ai.helper2.guide.Candidate
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.MemoryAnswerStore
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.guide.config.GuideProperties
import ai.helper2.guide.config.ModelStream
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.platform.ClientKey
import ai.helper2.platform.llm.LlmSettings
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.platform.resilience.Resilience4jPolicies
import ai.helper2.search.CachedHits
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.catalina.startup.Tomcat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.annotation.Configuration
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.EnableWebMvc

class GuideLeaveOnTheWireTest {

  @Configuration(proxyBeanMethods = false) @EnableWebMvc open class Mvc

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.systemUTC()
  private val meters = SimpleMeterRegistry()
  private val store = MemoryAnswerStore()
  private val quota = ClientQuota(3, clock, QuotaKeys("test-secret", clock))
  private val heartbeat = Duration.ofMillis(50)
  private val leaveGrace = Duration.ofSeconds(1)
  private val longerThanALeave = leaveGrace.plus(heartbeat.multipliedBy(4)).multipliedBy(2)
  private val localhost = ClientKey.of("127.0.0.1")
  private val closers = mutableListOf<() -> Unit>()

  @AfterTest
  fun shutdown() {
    closers.reversed().forEach { it() }
    executor.shutdownNow()
  }

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(
          hit(
            "hf",
            "https://huggingface.co/acme/whisper-small",
            title = "whisper-small",
            type = ResourceType.MODEL,
            description = "Speech recognition model you can self-host",
          )
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private class WaitsWhileItChooses : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    val choosing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    val checked = AtomicBoolean()

    override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech recognition")

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      choosing.countDown()
      try {
        release.await()
      } catch (_: InterruptedException) {
        stopped.countDown()
        Thread.currentThread().interrupt()
        throw GuideUnavailable("the model call was interrupted", GuideUnavailable.CANCELLED)
      }
      return GuideProposal(
        listOf(
          ProposedPick(
            candidates.first().key,
            "Self-hostable.",
            "Speech recognition model you can self-host",
          )
        )
      )
    }

    override fun verifyReasons(
      request: Need,
      claims: List<ReasonClaim>,
    ): Map<String, ReasonVerdict> {
      checked.set(true)
      return claims.associate { it.key to ReasonVerdict(true) }
    }
  }

  private val model = WaitsWhileItChooses()
  private var port = 0

  private fun settings(stream: ModelStream, thinking: GuideProperties.Thinking) =
    GuideProperties(
      600,
      30,
      30,
      Duration.ofSeconds(90),
      Duration.ofSeconds(40),
      Duration.ofSeconds(10),
      Duration.ofSeconds(2),
      heartbeat,
      Duration.ofHours(24),
      null,
      24,
      8192,
      stream,
      thinking,
      GuideProperties.Fairness(300, 2, 12),
    )

  private val planThinks = GuideProperties.Thinking(2048, 0, 0, 0)

  private fun aGuideThatStreamsAndThinks() = aGuideWith(ModelStream.ON, planThinks)

  private fun aGuideWith(stream: ModelStream, thinking: GuideProperties.Thinking) {
    port = tomcatWith(controller(settings(stream, thinking)))
  }

  private fun controller(settings: GuideProperties): GuideStreamController {
    val guide =
      AskGuide(
        SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock),
        model,
        clock,
        cache = GuideAnswerCache(Duration.ofHours(24), clock, store),
        meters = meters,
        quota = quota,
        stopsWhenNobodyWaits = settings.stopsAQuestionNobodyWaitsFor(),
      )
    val limits =
      GuideLimits(
          Resilience4jPolicies(
            CircuitBreakerRegistry.ofDefaults(),
            BulkheadRegistry.ofDefaults().also { it.bulkhead("guide") },
            RetryRegistry.ofDefaults(),
          ),
          Duration.ofSeconds(60),
          GuideLimits.ANSWER_GRACE,
          leaveGrace,
          clock,
          GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
        )
        .also { closers += it::destroy }
    return GuideStreamController(
      guide,
      GuideDtoMapper(LlmSettings("fake", "", "fast", "reasoning")),
      limits,
      meters,
      heartbeat,
    )
  }

  private fun tomcatWith(controller: GuideStreamController): Int {
    val web = AnnotationConfigWebApplicationContext()
    web.register(Mvc::class.java, GuideStreamWriters::class.java)
    web.addBeanFactoryPostProcessor { it.registerSingleton("guideStreamController", controller) }
    val tomcat = Tomcat()
    tomcat.setBaseDir(Files.createTempDirectory("guide-leave-on-the-wire").toString())
    tomcat.setPort(0)
    val context = tomcat.addContext("", null)
    Tomcat.addServlet(context, "dispatcher", DispatcherServlet(web)).apply {
      isAsyncSupported = true
      loadOnStartup = 1
    }
    context.addServletMappingDecoded("/", "dispatcher")
    tomcat.connector
    tomcat.start()
    closers += {
      tomcat.stop()
      tomcat.destroy()
    }
    return tomcat.connector.localPort
  }

  private fun ask(): Socket {
    val body = "{\"request\":\"speech to text I can self-host\"}"
    val socket = Socket("127.0.0.1", port)
    socket.soTimeout = 20_000
    socket
      .getOutputStream()
      .write(
        ("POST /api/v1/guide/stream HTTP/1.1\r\nHost: localhost\r\n" +
            "Content-Type: application/json\r\nAccept: text/event-stream\r\n" +
            "Content-Length: ${body.length}\r\n\r\n$body")
          .toByteArray(StandardCharsets.UTF_8)
      )
    socket.getOutputStream().flush()
    closers += socket::close
    return socket
  }

  private fun Socket.readUntil(text: String): String {
    val read = StringBuilder()
    val buffer = ByteArray(4096)
    while (!read.contains(text)) {
      val n = getInputStream().read(buffer)
      if (n < 0) break
      read.append(String(buffer, 0, n, StandardCharsets.UTF_8))
    }
    return read.toString()
  }

  private fun askUntilTheModelChooses(): Socket =
    ask().also {
      assertTrue(model.choosing.await(20, TimeUnit.SECONDS))
      assertTrue(it.readUntil("event:retrieved").startsWith("HTTP/1.1 200"))
    }

  private fun askTheQuestionInProgress(): Socket =
    ask().also { assertTrue(it.readUntil(":keep-alive").startsWith("HTTP/1.1 200")) }

  private fun questions(outcome: String) =
    meters.counter("helper2.guide.questions", "outcome", outcome).count()

  private fun givenBackAsAbandoned() =
    meters
      .counter("helper2.guide.quota.give_back", "reason", "abandoned", "outcome", "given_back")
      .count()

  private fun eventually(check: () -> Boolean): Boolean {
    val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (!check() && System.nanoTime() < giveUp) Thread.sleep(20)
    return check()
  }

  private fun theQuestionGoesOn() =
    !model.stopped.await(longerThanALeave.toMillis(), TimeUnit.MILLISECONDS)

  @Test
  fun `a browser that closes its socket stops its question, which is not cached and is given back`() {
    aGuideThatStreamsAndThinks()
    val asker = askUntilTheModelChooses()
    assertEquals(2, quota.left(localhost))

    asker.close()

    assertTrue(model.stopped.await(20, TimeUnit.SECONDS), "the worker is interrupted")
    assertTrue(eventually { questions("abandoned") == 1.0 })
    assertEquals(1.0, givenBackAsAbandoned())
    assertEquals(3, quota.left(localhost))
    assertEquals(0, store.puts)
    assertFalse(model.checked.get(), "no model call is started after the stop")
  }

  @Test
  fun `a second asker of the same question keeps it alive when the first closes its socket`() {
    aGuideThatStreamsAndThinks()
    val first = askUntilTheModelChooses()
    val second = askTheQuestionInProgress()

    first.close()

    assertTrue(theQuestionGoesOn())
    model.release.countDown()
    assertTrue(second.readUntil("event:answer").contains("whisper-small"))
    assertEquals(1, store.puts)
    assertEquals(0.0, questions("abandoned"))
  }

  @Test
  fun `an asker who closes its socket and asks again within the grace finds the question in progress`() {
    aGuideThatStreamsAndThinks()
    val before = askUntilTheModelChooses()

    before.close()
    val again = askTheQuestionInProgress()

    assertTrue(theQuestionGoesOn())
    model.release.countDown()
    assertTrue(again.readUntil("event:answer").contains("whisper-small"))
    assertEquals(1, store.puts)
    assertEquals(1.0, questions("answered"), "the model was asked once")
    assertEquals(0.0, questions("abandoned"))
  }

  enum class RunsOn(val stream: ModelStream, val planTokens: Int) {
    STREAM_OFF_THINKING_ON(ModelStream.OFF, 2048),
    STREAM_ON_THINKING_OFF(ModelStream.ON, 0),
    STREAM_OFF_THINKING_OFF(ModelStream.OFF, 0),
  }

  @ParameterizedTest
  @EnumSource(RunsOn::class)
  fun `without a stream that thinks, a closed socket does not stop the question and its answer is cached`(
    guide: RunsOn
  ) {
    aGuideWith(guide.stream, GuideProperties.Thinking(guide.planTokens, 0, 0, 0))
    val asker = askUntilTheModelChooses()

    asker.close()

    assertTrue(theQuestionGoesOn())
    model.release.countDown()
    assertTrue(eventually { store.puts == 1 })
    assertTrue(model.checked.get(), "the reason check ran")
    assertTrue(eventually { questions("answered") == 1.0 })
    assertEquals(0.0, questions("abandoned"))
    assertEquals(0.0, givenBackAsAbandoned())
    assertEquals(
      2,
      quota.left(localhost),
      "the answer has a pick: it counts, and asking again is free",
    )
  }
}
