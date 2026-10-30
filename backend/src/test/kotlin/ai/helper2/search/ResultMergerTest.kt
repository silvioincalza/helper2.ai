package ai.helper2.search

import ai.helper2.shared.DateStatus
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultMergerTest {

    private val merger = ResultMerger()

    @Test
    fun `same canonical url from two sources becomes one result with both sources`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/Acme/Tool"),
                hit("npm", "https://github.com/acme/tool.git"),
            ),
            contexts("github", "npm"),
        )
        assertEquals(1, merged.size)
        assertEquals(setOf("github", "npm"), merged[0].sources.map { it.sourceId }.toSet())
    }

    @Test
    fun `npm package linked to its repository merges with the github record`() {
        val merged = merger.merge(
            listOf(
                hit("npm", "https://www.npmjs.com/package/acme-mcp", repo = "git+https://github.com/acme/acme-mcp.git"),
                hit("github", "https://github.com/acme/acme-mcp"),
            ),
            contexts("github", "npm"),
        )
        assertEquals(1, merged.size)
        assertEquals("https://github.com/acme/acme-mcp", merged[0].canonicalUrl.value)
    }

    @Test
    fun `a package found without its repository keeps its own id, so monorepo packages never share one`() {
        val repo = "https://github.com/acme/monorepo"
        val a = merger.merge(listOf(hit("npm", "https://www.npmjs.com/package/@acme/a", repo = repo)), contexts("npm")).single()
        val b = merger.merge(listOf(hit("npm", "https://www.npmjs.com/package/@acme/b", repo = repo)), contexts("npm")).single()
        assertEquals("https://npmjs.com/package/@acme/a", a.canonicalUrl.value)
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `different resources are not merged`() {
        val merged = merger.merge(
            listOf(hit("github", "https://github.com/a/one"), hit("github", "https://github.com/a/two")), contexts("github"),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `conflicting licenses are kept as competing assertions not silently resolved`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/x", license = "MIT"),
                hit("npm", "https://github.com/a/x", license = "Apache-2.0"),
            ),
            contexts("github", "npm"),
        ).single()
        assertNull(merged.license)
        val conflict = merged.conflicts.single { it.field == "license" }
        assertEquals(setOf("MIT", "Apache-2.0"), conflict.values.map { it.value }.toSet())
        assertEquals(setOf("MIT", "Apache-2.0"), merged.assertedLicenses)
    }

    @Test
    fun `equivalent license spellings are not a conflict`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/x", license = "MIT License"),
                hit("npm", "https://github.com/a/x", license = "mit"),
            ),
            contexts("github", "npm"),
        ).single()
        assertEquals("MIT", merged.license)
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun `npm SEE LICENSE IN file is an unknown license, not a value or a conflict`() {
        listOf("SEE LICENSE IN LICENSE.md", "see license in LICENSE", "  SEE LICENSE IN ./LICENSE.txt").forEach { raw ->
            val alone = merger.merge(listOf(hit("npm", "https://github.com/a/x", license = raw)), contexts("npm")).single()
            assertNull(alone.license, raw)
            // The license filter files it under "unknown", not under a pseudo license value.
            assertEquals(listOf(alone), Filtering.apply(listOf(alone), SearchFilters(licenses = setOf(UNKNOWN))), raw)

            val withGithub = merger.merge(
                listOf(hit("github", "https://github.com/a/x", license = "MIT"), hit("npm", "https://github.com/a/x", license = raw)),
                contexts("github", "npm"),
            ).single()
            assertEquals("MIT", withGithub.license, raw)
            assertTrue(withGithub.conflicts.none { it.field == "license" }, raw)
        }
    }

    @Test
    fun `published is earliest and updated is latest with attribution`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/x", published = "2023-01-01T00:00:00Z", updated = "2026-01-01T00:00:00Z"),
                hit("npm", "https://github.com/a/x", published = "2024-01-01T00:00:00Z", updated = "2026-09-01T00:00:00Z"),
            ),
            contexts("github", "npm"),
        ).single()
        assertEquals("github", merged.published.sourceId)
        assertEquals("npm", merged.updated.sourceId)
        assertEquals(1, merged.published.alternatives.size)
    }

    @Test
    fun `two sources stating different published days make the date conflicting, different updates do not`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/x", published = "2023-01-01T00:00:00Z", updated = "2026-01-01T00:00:00Z"),
                hit("npm", "https://github.com/a/x", published = "2024-01-01T00:00:00Z", updated = "2026-09-01T00:00:00Z"),
            ),
            contexts("github", "npm"),
        ).single()
        assertEquals(DateStatus.CONFLICTING, merged.published.status)
        assertEquals(java.time.Instant.parse("2023-01-01T00:00:00Z"), merged.published.value)
        assertEquals(DateStatus.KNOWN, merged.updated.status)
    }

    @Test
    fun `sources agreeing on the published day are not a conflict`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/x", published = "2023-01-01T08:00:00Z"),
                hit("npm", "https://github.com/a/x", published = "2023-01-01T17:30:00Z"),
            ),
            contexts("github", "npm"),
        ).single()
        assertEquals(DateStatus.KNOWN, merged.published.status)
    }

    @Test
    fun `missing dates stay unknown and discovered is unknown until observed`() {
        val merged = merger.merge(listOf(hit("github", "https://github.com/a/x")), contexts("github")).single()
        assertEquals(DateStatus.UNKNOWN, merged.published.status)
        assertEquals(DateStatus.UNKNOWN, merged.updated.status)
        assertEquals(DateStatus.UNKNOWN, merged.discovered.status)
    }

    @Test
    fun `most specific type wins`() {
        val merged = merger.merge(
            listOf(
                hit("npm", "https://github.com/a/x", type = ResourceType.LIBRARY),
                hit("mcp", "https://github.com/a/x", type = ResourceType.MCP_SERVER),
            ),
            contexts("npm", "mcp"),
        ).single()
        assertEquals(ResourceType.MCP_SERVER, merged.type)
    }

    @Test
    fun `description comes from the preferred source and records its origin`() {
        val ctx = mapOf(
            "github" to SourceContext(descriptor("github", priority = 10), ServedFrom.LIVE),
            "npm" to SourceContext(descriptor("npm", priority = 20), ServedFrom.LIVE),
        )
        val merged = merger.merge(
            listOf(
                hit("npm", "https://github.com/a/x", description = "from npm"),
                hit("github", "https://github.com/a/x", description = "from github"),
            ),
            ctx,
        ).single()
        assertEquals("from github", merged.description)
    }

    @Test
    fun `freshness reflects how data was served`() {
        val live = merger.merge(listOf(hit("github", "https://github.com/a/x")), contexts("github")).single()
        val stale = merger.merge(
            listOf(hit("github", "https://github.com/a/x")), contexts("github", servedFrom = ServedFrom.STALE_CACHE),
        ).single()
        val curated = merger.merge(
            listOf(hit("curated", "https://example.com/p")),
            mapOf("curated" to SourceContext(descriptor("curated", mode = SourceMode.CURATED), ServedFrom.LIVE)),
        ).single()
        assertEquals(Freshness.LIVE, live.freshness)
        assertEquals(Freshness.STALE, stale.freshness)
        assertEquals(Freshness.CURATED, curated.freshness)
    }

    @Test
    fun `use cases are derived deterministically from source text when no source states them`() {
        val merged = merger.merge(
            listOf(hit("github", "https://github.com/a/x", title = "doc-translator", description = "Translate documents with an LLM")),
            contexts("github"),
        ).single()
        assertEquals(setOf("translation"), merged.useCases)
    }

    @Test
    fun `two npm packages naming the same repository stay two results, each keeping its repository link`() {
        val repo = "git+https://github.com/modelcontextprotocol/servers.git"
        val merged = merger.merge(
            listOf(
                hit("npm", "https://www.npmjs.com/package/@modelcontextprotocol/server-memory", repo = repo),
                hit("npm", "https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem", repo = repo),
            ),
            contexts("npm"),
        )
        assertEquals(2, merged.size)
        assertEquals(2, merged.map { it.id }.toSet().size)
        assertEquals(
            setOf("https://npmjs.com/package/@modelcontextprotocol/server-memory", "https://npmjs.com/package/@modelcontextprotocol/server-filesystem"),
            merged.map { it.canonicalUrl.value }.toSet(),
        )
        assertTrue(merged.all { r -> r.hits.single().repositoryUrl?.value == "https://github.com/modelcontextprotocol/servers" })
    }

    @Test
    fun `a repository shared by several packages stays its own result`() {
        val repo = "https://github.com/acme/monorepo"
        val merged = merger.merge(
            listOf(
                hit("github", repo),
                hit("npm", "https://www.npmjs.com/package/@acme/a", repo = repo),
                hit("npm", "https://www.npmjs.com/package/@acme/b", repo = repo),
            ),
            contexts("github", "npm"),
        )
        assertEquals(3, merged.size)
        assertEquals(listOf("github"), merged.single { it.canonicalUrl.value == repo }.sources.map { it.sourceId })
    }

    @Test
    fun `a package in a repository sub-directory does not merge with the repository`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/remotion-dev/remotion"),
                hit("npm", "https://www.npmjs.com/package/@remotion/player", repo = "https://github.com/remotion-dev/remotion/tree/main/packages/player"),
            ),
            contexts("github", "npm"),
        )
        assertEquals(2, merged.size)
        val pkg = merged.single { it.sources.single().sourceId == "npm" }
        assertEquals("https://npmjs.com/package/@remotion/player", pkg.canonicalUrl.value)
        assertEquals("https://github.com/remotion-dev/remotion/tree/main/packages/player", pkg.hits.single().repositoryUrl?.value)
    }

    @Test
    fun `the same package listed twice still joins its repository`() {
        val pkg = hit("npm", "https://www.npmjs.com/package/acme-mcp", repo = "https://github.com/acme/acme-mcp")
        val merged = merger.merge(listOf(pkg, pkg, hit("github", "https://github.com/acme/acme-mcp")), contexts("github", "npm"))
        assertEquals(1, merged.size)
        assertEquals("https://github.com/acme/acme-mcp", merged.single().canonicalUrl.value)
    }
}
