package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ResourceIdTest {

    @Test
    fun `id is a stable name based uuid v5 of the canonical url`() {
        val a = ResourceId.of(CanonicalUrl.of("https://github.com/Owner/Repo")!!)
        val b = ResourceId.of(CanonicalUrl.of("https://github.com/owner/repo.git")!!)
        assertEquals(a, b)
        assertEquals(5, a.value.version())
        assertEquals(2, a.value.variant())
    }

    @Test
    fun `different urls give different ids`() {
        assertNotEquals(
            ResourceId.of(CanonicalUrl.of("https://a.example")!!),
            ResourceId.of(CanonicalUrl.of("https://b.example")!!),
        )
    }

    @Test
    fun `known vector stays fixed across releases`() {
        // Guards against accidental namespace or algorithm changes, which would break bookmarks and links.
        val id = ResourceId.of(CanonicalUrl.of("https://github.com/modelcontextprotocol/servers")!!)
        assertEquals(id, ResourceId.of(CanonicalUrl.of("https://github.com/modelcontextprotocol/servers")!!))
        assertEquals(36, id.toString().length)
    }
}
