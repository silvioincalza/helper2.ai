package ai.searchavo.scout

import ai.searchavo.TestcontainersConfiguration
import ai.searchavo.platform.resilience.Resilience
import ai.searchavo.scout.admission.AdmissionPolicy
import ai.searchavo.scout.usecase.RunScout
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.CategoryMatcher
import ai.searchavo.shared.ResourceId
import ai.searchavo.shared.ResourceType
import ai.searchavo.sources.SourceFixtures
import ai.searchavo.sources.arxiv.scout.ArxivPaperReader
import ai.searchavo.sources.arxiv.scout.ArxivPaperReaderTest
import ai.searchavo.sources.classifier.RepoClassifier
import ai.searchavo.sources.github.scout.GitHubAwesomeCollector
import ai.searchavo.sources.huggingface.scout.HuggingFaceModelReader
import ai.searchavo.sources.liveweb.scout.CoursePages
import ai.searchavo.sources.npm.scout.NpmPackageReader
import ai.searchavo.sources.support.RunInterval
import ai.searchavo.sources.support.SourceSettings
import ai.searchavo.testing.FixtureServer
import ai.searchavo.testing.TestHttp
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
      SourceSettings(true, github.baseUrl(), "t", Duration.ofSeconds(5), Duration.ZERO, null),
      TestHttp.restClientBuilder(Duration.ofSeconds(5), ObservationRegistry.NOOP),
      Clock.systemUTC(),
      Resilience.NONE,
      RunInterval(Duration.ofDays(1)),
      listOf("topic:awesome-list topic:llm"),
      GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
      GitHubAwesomeCollector.Deep(
        2,
        30,
        Duration.ofMinutes(5),
        10_000_000L,
        500,
        100,
        200,
        100,
        50,
        listOf("arxiv.org", "npmjs.com"),
      ),
      RepoClassifier.Heuristic(),
      categories,
      SimpleMeterRegistry(),
      SeedDiscoveryHosts.store(),
      InMemoryListSeedStore(),
      SourceFixtures.SHADOW_LIBRARIES,
      GitHubAwesomeCollector.LinkRules(
        CoursePages(listOf("courses-$run.org=Courses $run")),
        HuggingFaceModelReader(
          TestHttp.restClientBuilder(Duration.ofSeconds(5), ObservationRegistry.NOOP)
            .baseUrl(github.baseUrl())
            .build(),
          Resilience.NONE,
          "collector-github-awesome",
          Clock.systemUTC(),
        ),
        AdmissionPolicy(),
        ArxivPaperReader(
          fixtureClient(),
          Resilience.NONE,
          "collector-github-awesome",
          Clock.systemUTC(),
          { _, _ -> true },
          {},
        ),
        NpmPackageReader(
          fixtureClient(),
          Resilience.NONE,
          "collector-github-awesome",
          Clock.systemUTC(),
        ),
      ),
    )

  private fun fixtureClient() =
    TestHttp.restClientBuilder(Duration.ofSeconds(5), ObservationRegistry.NOOP)
      .baseUrl(github.baseUrl())
      .build()

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

  private val model = "https://huggingface.co/hub-$run/whisper"
  private val course = "https://courses-$run.org/learn/generative-ai"
  private val paper = "https://arxiv.org/abs/2212.$run"

  private fun routesWithOneLinkOfEachKind() {
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
        ## Models
        - [Whisper $run]($model) - Speech to text. [Paper]($paper)
        ## Learn
        - [Generative AI course $run]($course) - A course.
        ## Tools
        ### Text-to-speech
        - [Voice $run]($site) - Speaks.
        - [Store](https://github.com/$engine) - Embedded.
        """
          .trimIndent()
      ),
    )
    github.route(
      "/api/models/hub-$run/whisper",
      FixtureServer.Reply.ok(
        """
        {"id":"hub-$run/whisper","likes":120,"downloads":5000,
         "tags":["transformers","license:mit"],"pipeline_tag":"automatic-speech-recognition",
         "createdAt":"2024-01-01T00:00:00Z","lastModified":"2026-10-01T00:00:00Z"}
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

  private fun resourceId(url: String): UUID = ResourceId.of(CanonicalUrl.of(url)!!).value

  private fun resourceType(url: String): String? =
    jdbc
      .sql("SELECT type FROM resource WHERE id = :id")
      .param("id", resourceId(url))
      .query(String::class.java)
      .optional()
      .orElse(null)

  private fun listHeadingOf(url: String): String? =
    jdbc
      .sql(
        "SELECT heading_path FROM awesome_list_entry WHERE list_id = :list AND resource_id = :entry"
      )
      .param("list", id(seed))
      .param("entry", resourceId(url))
      .query(String::class.java)
      .optional()
      .orElse(null)

  private fun reviewItems(kind: String, url: String): List<JsonNode> =
    jdbc
      .sql("SELECT details FROM review_item WHERE kind = :kind AND candidate_url = :u")
      .param("kind", kind)
      .param("u", url)
      .query(String::class.java)
      .list()
      .filterNotNull()
      .map { json.readTree(it) }

  private fun reviewItemsOf(url: String): Int =
    jdbc
      .sql("SELECT count(*) FROM review_item WHERE candidate_url = :u")
      .param("u", url)
      .query(Int::class.javaObjectType)
      .single()

  @Test
  fun `every link of a list entry goes to the rule of its kind`() {
    routesWithOneLinkOfEachKind()

    scout().tryStart()

    assertEquals(ResourceType.MODEL.name, resourceType(model))
    assertEquals("Models", listHeadingOf(model))
    assertEquals("Tools > Text-to-speech", listHeadingOf("https://github.com/$engine"))
    val courses = reviewItems("COURSE_CANDIDATE", course)
    assertEquals(1, courses.size)
    assertEquals(ResourceType.COURSE.name, courses.single().path("type").asString())
    assertEquals("Courses $run", courses.single().path("platform").asString())
    val products = reviewItems("RESOURCE_CANDIDATE", site)
    assertEquals(1, products.size)
    val foundIn = products.single().path("foundBy")[0].path("awesome")
    assertEquals("https://github.com/$seed", foundIn.path("list").asString())
    assertEquals("Tools > Text-to-speech", foundIn.path("section").asString())
    assertEquals(0, reviewItemsOf(model))
    assertEquals(0, reviewItemsOf(paper))
    assertEquals(null, resourceType(paper))
    assertEquals(null, resourceType(course))
    assertEquals(null, resourceType(site))
  }

  private fun routesForAList(readme: String) {
    github.route(
      "/search/repositories",
      FixtureServer.Reply.ok(
        """
        {"items":[{"full_name":"$seed","html_url":"https://github.com/$seed","stargazers_count":5000,
          "description":"Curated LLM tools","topics":["llm"]}]}
        """
      ),
    )
    github.route("/repos/$seed/readme", FixtureServer.Reply.ok(readme))
    github.route("/graphql", FixtureServer.Reply.ok("""{"data":{}}"""))
  }

  private fun recordsOf(url: String): List<String> =
    jdbc
      .sql("SELECT source_id || ' ' || title FROM source_record WHERE resource_id = :id")
      .param("id", resourceId(url))
      .query(String::class.java)
      .list()
      .filterNotNull()

  @Test
  fun `an arXiv paper a list links to is read by its id and is one paper whatever the link`() {
    val id = "24${run.filter { it.isDigit() }.padEnd(2, '0').take(2)}.${(10000..99999).random()}"
    val paper = "https://arxiv.org/abs/$id"
    routesForAList(
      """
      # Awesome LLM
      ## Papers
      - [Paper $run](https://arxiv.org/abs/${id}v3) - Agents. [[pdf]](https://arxiv.org/pdf/$id.pdf)
      - [Unknown](https://arxiv.org/abs/0001.00001)
      """
        .trimIndent()
    )
    github.route(
      "/api/query",
      FixtureServer.Reply.ok(
        ArxivPaperReaderTest.feed(ArxivPaperReaderTest.entry(id, "Paper $run", "cs.MA"))
      ),
    )

    scout().tryStart()

    assertEquals(ResourceType.PAPER.name, resourceType(paper))
    assertEquals(listOf("github-awesome Paper $run"), recordsOf(paper))
    assertEquals("Papers", listHeadingOf(paper))
    assertEquals(null, resourceType("https://arxiv.org/abs/0001.00001"))
    assertEquals(1, github.requests.count { it.startsWith("/api/query") })
  }

  @Test
  fun `an npm package a list links to is read by its name and lands on the right resource`() {
    val scoped = "@acme-$run/agents"
    val repository = "https://github.com/acme-$run/agents-js"
    val alone = "llm-kit-$run"
    val old = "old-llm-$run"
    routesForAList(
      """
      # Awesome LLM
      ## SDKs
      - [Agents](https://www.npmjs.com/package/$scoped) - Agent SDK.
      - [Kit](https://www.npmjs.com/package/$alone) - LLM kit.
      - [Old](https://www.npmjs.com/package/$old) - Old client.
      """
        .trimIndent()
    )
    github.route(
      "/$scoped/latest",
      FixtureServer.Reply.ok(
        """
        {"name":"$scoped","description":"Agent SDK for LLM apps","keywords":["llm","ai"],
         "license":"MIT","repository":{"url":"git+$repository.git"}}
        """
      ),
    )
    github.route(
      "/$alone/latest",
      FixtureServer.Reply.ok(
        """
        {"name":"$alone","description":"A kit for LLM apps","keywords":["llm","ai"],
         "license":"Apache-2.0","repository":"https://github.com/someone-$run/misc"}
        """
      ),
    )
    github.route(
      "/$old/latest",
      FixtureServer.Reply.ok(
        """
        {"name":"$old","description":"An old LLM client","keywords":["llm","ai"],
         "deprecated":"Use another one"}
        """
      ),
    )

    scout().tryStart()

    assertEquals(listOf("github-awesome $scoped"), recordsOf(repository))
    assertEquals("SDKs", listHeadingOf(repository))
    assertEquals(null, resourceType("https://npmjs.com/package/$scoped"))
    assertEquals(listOf("github-awesome $alone"), recordsOf("https://npmjs.com/package/$alone"))
    assertEquals("SDKs", listHeadingOf("https://npmjs.com/package/$alone"))
    assertEquals(null, resourceType("https://npmjs.com/package/$old"))
    assertEquals(null, resourceType("https://github.com/someone-$run/misc"))
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
