package ai.helper2.catalog

import ai.helper2.TestcontainersConfiguration
import ai.helper2.catalog.internal.CatalogRetention
import ai.helper2.catalog.internal.ResourceStore
import ai.helper2.catalog.internal.ResourceStore.DataOrigin
import ai.helper2.catalog.usecase.IndexAwesomeLists
import ai.helper2.catalog.usecase.IndexResources
import ai.helper2.search.ListMatch
import ai.helper2.search.NOW
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceId
import ai.helper2.shared.ResourceType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Search inside awesome lists (ADR 0055): the index writes and the catalog search, on PostgreSQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AwesomeListSearchIT {

  @Autowired lateinit var indexResources: IndexResources
  @Autowired lateinit var indexLists: IndexAwesomeLists
  @Autowired lateinit var store: ResourceStore
  @Autowired lateinit var retention: CatalogRetention
  @Autowired lateinit var jdbc: JdbcClient

  /** A word no other test uses, so each test sees only its own rows. */
  private val word = "zq" + UUID.randomUUID().toString().replace("-", "").take(10)
  private val run = word.takeLast(6)
  private val list = "https://github.com/lists-$run/awesome-things"
  private val direct = "https://github.com/direct-$run/tool"
  private val listed = "https://github.com/listed-$run/tool"
  private val other = "https://github.com/other-$run/tool"

  private fun url(u: String) = CanonicalUrl.of(u)!!

  private fun id(u: String): UUID = ResourceId.of(url(u)).value

  private fun stars(n: Long) =
    listOf(PopularityMetric(PopularityKind.GITHUB_STARS, n, "github", NOW))

  private fun catalog() {
    val hits =
      listOf(
        hit("github", list, title = "lists-$run/awesome-things", type = ResourceType.ARTICLE)
          .copy(popularity = stars(5_000)),
        hit("github", direct, title = "direct-$run/tool", description = "A $word engine for agents")
          .copy(popularity = stars(100)),
        hit("github", listed, title = "listed-$run/tool", description = "Fast storage")
          .copy(popularity = stars(100)),
        hit("github", other, title = "other-$run/tool", description = "Something else")
          .copy(popularity = stars(100)),
      )
    indexResources.index(
      ResultMerger()
        .merge(hits, mapOf("github" to SourceContext(descriptor("github"), ServedFrom.LIVE))),
      "github",
    )
  }

  private fun listText(vararg entries: AwesomeListText.Entry) =
    AwesomeListText(url(list), "Awesome Things\nTools\nVector databases\n", entries.toList())

  private fun entry(u: String, text: String, heading: String) =
    AwesomeListText.Entry(url(u), text, heading)

  private fun entries(): List<Pair<UUID, String>> =
    jdbc
      .sql(
        "SELECT resource_id, heading_path FROM awesome_list_entry WHERE list_id = :l ORDER BY heading_path"
      )
      .param("l", id(list))
      .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
      .list()

  private fun search(text: String): List<ResourceStore.Found> {
    val q = QueryAnalyzer().analyze(text)
    return store.searchFound(
      text,
      q.requiredVariants,
      q.expandedTerms,
      q.synonyms,
      null,
      setOf(DataOrigin.INDEXED, DataOrigin.OBSERVED),
      50,
      null,
    )
  }

  @Test
  fun `a pass replaces the list's entries and skips pages not in the catalog`() {
    catalog()

    val written =
      indexLists.replace(
        listOf(
          listText(
            entry(listed, "Listed - a $word store", "Tools > Vector databases"),
            entry(other, "Other - unrelated", "Tools"),
            entry("https://github.com/nobody-$run/missing", "Missing", "Tools"),
          )
        )
      )

    assertEquals(2, written)
    assertEquals(
      listOf(id(other) to "Tools", id(listed) to "Tools > Vector databases"),
      entries(),
    )

    // The next pass no longer lists "other": its row goes.
    indexLists.replace(listOf(listText(entry(listed, "Listed - a $word store", "Tools"))))

    assertEquals(listOf(id(listed) to "Tools"), entries())
  }

  @Test
  fun `a list not in the catalog writes nothing`() {
    val written = indexLists.replace(listOf(listText(entry(listed, "Listed", "Tools"))))

    assertEquals(0, written)
    assertEquals(
      0L,
      jdbc
        .sql("SELECT count(*) FROM awesome_list WHERE list_id = :l")
        .param("l", id(list))
        .query(Long::class.java)
        .single(),
    )
  }

  @Test
  fun `rows go with the list and with the resource`() {
    catalog()
    indexLists.replace(
      listOf(listText(entry(listed, "Listed", "Tools"), entry(other, "Other", "Tools")))
    )

    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id(other)).update()
    assertEquals(listOf(id(listed) to "Tools"), entries())

    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id(list)).update()
    assertEquals(emptyList(), entries())
    assertEquals(
      0L,
      jdbc
        .sql("SELECT count(*) FROM awesome_list WHERE list_id = :l")
        .param("l", id(list))
        .query(Long::class.java)
        .single(),
    )
  }

  @Test
  fun `retention deletes rows no pass refreshed`() {
    catalog()
    indexLists.replace(listOf(listText(entry(listed, "Listed", "Tools"))))
    jdbc
      .sql(
        "UPDATE awesome_list_entry SET updated_at = now() - interval '40 days' WHERE list_id = :l"
      )
      .param("l", id(list))
      .update()
    jdbc
      .sql("UPDATE awesome_list SET updated_at = now() - interval '40 days' WHERE list_id = :l")
      .param("l", id(list))
      .update()

    val deleted = retention.deleteListIndexUpdatedBefore(Instant.now().minusSeconds(30L * 86_400))

    assertTrue(deleted >= 2, "$deleted")
    assertEquals(emptyList(), entries())
  }

  @Test
  fun `a list-only match is found and says where, below a direct match`() {
    catalog()
    indexLists.replace(
      listOf(listText(entry(listed, "Listed - a $word store", "Tools > Vector databases")))
    )

    val found = search(word).filter { it.row().title().contains(run) }

    assertEquals(listOf("direct-$run/tool", "listed-$run/tool"), found.map { it.row().title() })
    assertNull(found[0].foundIn())
    assertEquals(
      ListMatch("lists-$run/awesome-things", "Tools > Vector databases"),
      found[1].foundIn(),
    )
  }

  @Test
  fun `a list is found by its headings`() {
    catalog()
    indexLists.replace(
      listOf(AwesomeListText(url(list), "Awesome Things\n$word section\n", listOf()))
    )

    val found = search(word).filter { it.row().title().contains(run) }

    assertEquals(
      listOf("direct-$run/tool", "lists-$run/awesome-things"),
      found.map { it.row().title() },
    )
    assertNull(found[1].foundIn(), "a list found by its headings has no entry to point to")
  }

  @Test
  fun `no column anywhere keeps list text, only tsvectors and the heading path`() {
    val columns =
      jdbc
        .sql(
          """
          SELECT table_name || '.' || column_name || ':' || data_type FROM information_schema.columns
          WHERE table_schema = current_schema() AND table_name IN ('awesome_list', 'awesome_list_entry')
          ORDER BY table_name, ordinal_position
          """
        )
        .query(String::class.java)
        .list()

    assertEquals(
      listOf(
        "awesome_list.list_id:uuid",
        "awesome_list.search_vector:tsvector",
        "awesome_list.updated_at:timestamp with time zone",
        "awesome_list_entry.list_id:uuid",
        "awesome_list_entry.resource_id:uuid",
        "awesome_list_entry.heading_path:text",
        "awesome_list_entry.search_vector:tsvector",
        "awesome_list_entry.updated_at:timestamp with time zone",
      ),
      columns,
    )
  }

  @Test
  fun `the entry line is nowhere in the database as text`() {
    catalog()
    val secret = "${word}unique sentence"
    indexLists.replace(listOf(listText(entry(listed, "Listed - $secret", "Tools"))))

    val textColumns =
      jdbc
        .sql(
          """
          SELECT table_name, column_name FROM information_schema.columns
          WHERE table_schema = current_schema() AND data_type IN ('text', 'character varying', 'jsonb')
          """
        )
        .query { rs, _ -> rs.getString(1) to rs.getString(2) }
        .list()
    val holders = textColumns.filter { (table, column) ->
      // Identifiers come from information_schema, never from input.
      jdbc
        .sql("SELECT EXISTS (SELECT 1 FROM \"$table\" WHERE \"$column\"::text LIKE :p)")
        .param("p", "%${secret}%")
        .query(Boolean::class.java)
        .single()
    }

    assertEquals(emptyList(), holders)
  }
}
