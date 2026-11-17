package ai.helper2.catalog

import ai.helper2.search.NOW
import ai.helper2.search.SourceHit
import ai.helper2.search.hit
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceRecordInfoTest {

  private val registry = "https://registry.modelcontextprotocol.io/v0.1/servers/"

  @Test
  fun `a record that states its repository shows it`() {
    val info =
      ResourceDetail.SourceRecordInfo.of(
        row(
          hit(
            "mcp-registry",
            "${registry}io.github.codespar%2Fmcp-adyen",
            repo = "https://github.com/codespar/mcp-dev-latam",
            name = "io.github.codespar/mcp-adyen",
          )
        )
      )
    assertEquals(URI("https://github.com/codespar/mcp-dev-latam"), info.repositoryUrl())
    assertEquals("io.github.codespar/mcp-adyen", info.externalId())
  }

  @Test
  fun `a record without a repository shows none`() {
    assertNull(
      ResourceDetail.SourceRecordInfo.of(row(hit("github", "https://github.com/a/b")))
        .repositoryUrl()
    )
  }

  private fun row(hit: SourceHit) =
    RecordRow(
      UUID.randomUUID(),
      hit.sourceId,
      hit.externalId,
      UUID.randomUUID(),
      hit.url.toString(),
      NOW,
      "fingerprint",
      null,
      hit,
    )
}
