package ai.helper2.scout

import ai.helper2.TestcontainersConfiguration
import ai.helper2.platform.resilience.Resilience
import ai.helper2.scout.usecase.RunScout
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.CategoryMatcher
import ai.helper2.shared.ResourceId
import ai.helper2.shared.ResourceType
import ai.helper2.sources.classifier.RepoClassifier
import ai.helper2.sources.github.scout.GitHubAwesomeCollector
import ai.helper2.sources.support.RunInterval
import ai.helper2.sources.support.SourceSettings
import ai.helper2.testing.FixtureServer
import ai.helper2.testing.TestHttp
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AwesomeDeepSearchIT {

  @Autowired lateinit var catalog: CatalogIndex
  @Autowired lateinit var lists: ListIndex
  @Autowired lateinit var review: CandidateReview
  @Autowired lateinit var categories: CategoryMatcher
  @Autowired lateinit var jdbc: JdbcClient
  @Autowired lateinit var json: ObjectMapper

  private val run = UUID.randomUUID().toString().replace("-", "").take(8)
  private val seed = "seed-$run/awesome-llm"
  private val nested = "nested-$run/rag-lists"
  private val engine = "engine-$run/vectorstore"
  private val site = "https://voice-$run.ai"

  private val github = FixtureServer()

  @AfterEach
  fun stop() {
    github.close()
  }

  private fun routes() {
    github.route(
      "/search/repositories",
      FixtureServer.Reply.ok(
        """
        {"items":[{"full_name":"$seed","html_url":"https://github.com/$seed","stargazers_count":5000,
          "description":"Curated LLM tools","topics":["llm"]}]}
        """
      ),
    )
    github.route(
      "/repos/$seed/readme",
      FixtureServer.Reply.ok(
        """
        # Awesome LLM
        ## Tools
        ### Text-to-speech
        - [Voice $run]($site/?utm_source=awesome) - Speaks.
        ## Related lists
        - [RAG lists](https://github.com/$nested)
        """
          .trimIndent()
      ),
    )
    github.route(
      "/repos/$nested/readme",
      FixtureServer.Reply.ok(
        """
        # RAG lists
        ## Storage
        ### Vector databases
        - [Store](https://github.com/$engine) - Embedded.
        - [Back](https://github.com/$seed)
        """
          .trimIndent()
      ),
    )
    github.route(
      "/repos/$nested",
      FixtureServer.Reply.ok(
        """
        {"full_name":"$nested","html_url":"https://github.com/$nested","stargazers_count":700,
         "description":"Curated RAG resources","topics":["rag"],"archived":false,"fork":false}
        """
      ),
    )
    github.route(
      "/graphql",
      FixtureServer.Reply.ok(
        """
        {"data":{"r0":{"nameWithOwner":"$engine","url":"https://github.com/$engine",
          "description":"Fast storage","stargazerCount":900,"isArchived":false,
          "isFork":false,"repositoryTopics":{"nodes":[{"topic":{"name":"llm"}}]}}}}
        """
      ),
    )
  }

  private fun collector() =
    GitHubAwesomeCollector(
      SourceSettings(true, github.baseUrl(), "t", Duration.ofSeconds(5), Duration.ZERO),
      TestHttp.restClientBuilder(Duration.ofSeconds(5), ObservationRegistry.NOOP),
      Clock.systemUTC(),
      Resilience.NONE,
      RunInterval(Duration.ofDays(1)),
      listOf("topic:awesome-list topic:llm"),
      GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
      GitHubAwesomeCollector.Deep(2, 30, Duration.ofMinutes(5), 10_000_000L, 500, 50, listOf()),
      RepoClassifier.Heuristic(),
      categories,
      SimpleMeterRegistry(),
      SeedDiscoveryHosts.store(),
    )

  private class MemStore : ScoutStore {
    val cursors = mutableMapOf<String, ScoutCursor>()

    override fun cursor(sourceId: String) = cursors[sourceId]

    override fun saveCursor(sourceId: String, cursor: ScoutCursor) {
      cursors[sourceId] = cursor
    }

    override fun save(run: RunSummary) {}

    override fun recent(limit: Int) = emptyList<RunSummary>()

    override fun markInterrupted(at: Instant) = 0
  }

  private fun scout() =
    RunScout(
      collectors = listOf(collector()),
      store = MemStore(),
      catalog = catalog,
      enrichment = null,
      clock = Clock.systemUTC(),
      lock =
        object : RunLock {
          override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
        },
      review = review,
      lists = lists,
    )

  private fun id(fullName: String): UUID =
    ResourceId.of(CanonicalUrl.of("https://github.com/$fullName")!!).value

  private fun typeOf(fullName: String): String? =
    jdbc
      .sql("SELECT type FROM resource WHERE id = :id")
      .param("id", id(fullName))
      .query(String::class.java)
      .optional()
      .orElse(null)

  private fun categoriesOf(fullName: String): List<String> =
    jdbc
      .sql("SELECT value FROM resource_label WHERE resource_id = :id AND kind = 'CATEGORY'")
      .param("id", id(fullName))
      .query(String::class.java)
      .list()
      .filterNotNull()

  private fun headingOf(list: String, entry: String): String? =
    jdbc
      .sql(
        "SELECT heading_path FROM awesome_list_entry WHERE list_id = :list AND resource_id = :entry"
      )
      .param("list", id(list))
      .param("entry", id(entry))
      .query(String::class.java)
      .optional()
      .orElse(null)

  private fun candidateItems(): List<String> =
    jdbc
      .sql(
        "SELECT details FROM review_item WHERE kind = 'RESOURCE_CANDIDATE' AND candidate_url = :u"
      )
      .param("u", site)
      .query(String::class.java)
      .list()
      .filterNotNull()

  @Test
  fun `a nested list is indexed and a product link joins the waiting discovery candidate`() {
    routes()
    val url = CanonicalUrl.of(site)!!
    review.propose(
      "discovery-voice-and-audio",
      listOf(
        Candidate(
          url,
          "Voice $run",
          "An AI voice studio.",
          "voice-and-audio",
          ResourceType.PRODUCT,
          setOf("voice-and-audio"),
          mapOf("discovery" to mapOf("query" to "ai voice")),
        )
      ),
    )

    scout().tryStart()

    assertEquals(ResourceType.ARTICLE.name, typeOf(nested))
    assertEquals("Storage > Vector databases", headingOf(nested, engine))
    assertEquals(listOf("rag-and-vector-databases"), categoriesOf(engine))
    val items = candidateItems()
    assertEquals(1, items.size)
    val details = json.readTree(items.single())
    assertEquals("discovery-voice-and-audio", details.path("sourceId").asString())
    val finders = details.path("foundBy")
    assertEquals<List<String?>>(
      listOf("discovery-voice-and-audio", "github-awesome"),
      (finders as Iterable<JsonNode>).map { it.path("sourceId").asString() },
    )
    val awesome = finders[1].path("awesome")
    assertEquals("https://github.com/$seed", awesome.path("list").asString())
    assertEquals("Tools > Text-to-speech", awesome.path("section").asString())
    assertEquals<List<String?>>(
      listOf("voice-and-audio"),
      (details.path("tags") as Iterable<JsonNode>).map { it.asString() },
    )
  }

  private fun product(host: String) =
    Candidate(
      CanonicalUrl.of("https://$host-$run.ai")!!,
      host,
      null,
      null,
      ResourceType.PRODUCT,
      emptySet(),
      emptyMap(),
    )

  @Test
  fun `past the daily cap a link opens nothing new but still joins a waiting item`() {
    val source = "capped-$run"
    review.propose("discovery-coding", listOf(product("waiting")))

    val first = review.propose(source, listOf(product("a"), product("b"), product("c")), 2)
    val later = review.propose(source, listOf(product("d"), product("waiting")), 2)

    assertEquals(Proposal(2, 1), first)
    assertEquals(Proposal(0, 1), later)
    val waiting =
      jdbc
        .sql("SELECT details FROM review_item WHERE candidate_url = :u")
        .param("u", "https://waiting-$run.ai")
        .query(String::class.java)
        .single()
    assertEquals<List<String?>>(
      listOf("discovery-coding", source),
      (json.readTree(waiting).path("foundBy") as Iterable<JsonNode>).map {
        it.path("sourceId").asString()
      },
    )
    val opened =
      jdbc
        .sql("SELECT count(*) FROM review_item WHERE candidate_url LIKE :u")
        .param("u", "https://_-$run.ai")
        .query(Int::class.javaObjectType)
        .single()
    assertEquals(2, opened)
  }
}
