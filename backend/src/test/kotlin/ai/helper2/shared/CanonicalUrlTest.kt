package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CanonicalUrlTest {

    private fun canon(raw: String) = CanonicalUrl.of(raw)?.value

    @Test
    fun `normalizes scheme host www trailing slash and fragment`() {
        assertEquals("https://example.com/tool", canon("HTTP://WWW.Example.com/tool/#pricing"))
    }

    @Test
    fun `drops tracking parameters and sorts the rest`() {
        assertEquals(
            "https://example.com/p?a=1&b=2",
            canon("https://example.com/p?utm_source=x&b=2&ref=hn&a=1&gclid=z"),
        )
    }

    @Test
    fun `github urls collapse to lowercase owner and repo`() {
        assertEquals("https://github.com/owner/repo", canon("https://github.com/Owner/Repo.git"))
        assertEquals("https://github.com/owner/repo", canon("git+https://github.com/Owner/Repo/tree/main/src"))
        assertEquals("https://github.com/owner/repo", canon("git://github.com/owner/repo.git"))
    }

    @Test
    fun `keeps case of non github paths`() {
        assertEquals("https://huggingface.co/Qwen/Qwen3-8B", canon("https://huggingface.co/Qwen/Qwen3-8B/"))
    }

    @Test
    fun `drops default port and keeps root slash free`() {
        assertEquals("https://example.com", canon("https://example.com:443/"))
        assertEquals("https://example.com:8443/x", canon("https://example.com:8443/x"))
    }

    @Test
    fun `rejects non web schemes and garbage`() {
        assertNull(canon("javascript:alert(1)"))
        assertNull(canon("ftp://example.com/file"))
        assertNull(canon("not a url"))
        assertNull(canon(""))
        assertNull(canon("https://"))
    }

    @Test
    fun `different resources stay different`() {
        assertNotEquals(canon("https://github.com/a/b"), canon("https://github.com/a/c"))
    }
}
