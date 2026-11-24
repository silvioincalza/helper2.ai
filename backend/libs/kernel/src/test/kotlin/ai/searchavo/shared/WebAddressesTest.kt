package ai.searchavo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WebAddressesTest {

  private fun verdict(raw: String): String =
    when (val checked = WebAddresses.check(raw)) {
      is WebAddresses.Checked.Public -> "PUBLIC"
      is WebAddresses.Checked.Refused -> checked.refusal.name
    }

  @ParameterizedTest
  @CsvSource(
    "https://www.Acme.ai/?utm_source=list#top, PUBLIC",
    "https://acme.ai/pricing, PUBLIC",
    "https://aws.amazon.com/bedrock/, PUBLIC",
    "https://acme.ai/studio?plan=pro, PUBLIC",
    "https://acme.ai/blog/launch, PUBLIC",
    "https://docs.github.com/en, PUBLIC",
    "https://bit.ly/3abc, PUBLIC",
    "https://Blog.Acme.dev/Best-AI-Tools, PUBLIC",
    "https://www.acme.dev/lists/rag/, PUBLIC",
    "https://acme.dev/lists?page=2, PUBLIC",
    "https://gitlab.com/acme/awesome-rag, PUBLIC",
    "https://acme.dev/list#tools, PUBLIC",
    "https://acme.github.io/, PUBLIC",
    "https://www.acme.co.uk/pricing, PUBLIC",
    "https://go.dev/, PUBLIC",
    "https://co.dev/, PUBLIC",
    "https://net.dev/, PUBLIC",
    "https://net.ai/, NOT_A_HOST_NAME",
    "https://com.au/, NOT_A_HOST_NAME",
    "https://go.jp/, NOT_A_HOST_NAME",
    "http://acme.ai, NOT_HTTPS",
    "http://blog.acme.dev/best-ai-tools, NOT_HTTPS",
    "ftp://blog.acme.dev/best-ai-tools, NOT_HTTPS",
    "'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==', NOT_HTTPS",
    "javascript:alert(1), NOT_HTTPS",
    "mailto:hi@acme.ai, NOT_HTTPS",
    "//acme.ai/, NOT_HTTPS",
    "https://93.184.216.34/, IP_ADDRESS",
    "https://127.0.0.1/, IP_ADDRESS",
    "'https://[::1]/', IP_ADDRESS",
    "https://127.1/, IP_ADDRESS",
    "https://0x7f.0x0.0x0.0x1/, IP_ADDRESS",
    "https://2130706433.0/, IP_ADDRESS",
    "https://2130706433/list, IP_ADDRESS",
    "https://user:secret@acme.ai/, USER_INFO",
    "https://acme.ai@evil.ai/, USER_INFO",
    "https://user@acme.dev/list, USER_INFO",
    "https://acme.ai:8443/, PORT",
    "https://acme.dev:443/list, PUBLIC",
    "https://localhost/, PRIVATE_HOST",
    "https://printer.local/, PRIVATE_HOST",
    "https://metadata.google.internal/, PRIVATE_HOST",
    "https://wiki.internal/list, PRIVATE_HOST",
    "https://wiki.corp/, PRIVATE_HOST",
    "https://router.home.arpa/, PRIVATE_HOST",
    "https://acme.example/, PRIVATE_HOST",
    "https://github.com.evil.example/acme/awesome-rag, PRIVATE_HOST",
    "https://intranet/, PRIVATE_HOST",
    "https://xn--pple-43d.com/, PUNYCODE",
    "https://www.xn--80ak6aa92e.com/, PUNYCODE",
    "https://acme.xn--p1ai/, PUNYCODE",
    "https://acme.ai./, NOT_A_HOST_NAME",
    "https://acme.dev./list, NOT_A_HOST_NAME",
    "https://acme.a1/, NOT_A_HOST_NAME",
    "https://co.uk/, NOT_A_HOST_NAME",
    "'https://acme .ai/', MALFORMED",
    "https://acme_ai.com/, MALFORMED",
    "https://acme.dev/a list, MALFORMED",
    "'', MALFORMED",
  )
  fun `a web address is public or refused for one reason`(raw: String, verdict: String) {
    assertEquals(verdict, verdict(raw), raw)
  }

  @Test
  fun `a very long address or one with a hidden character is malformed`() {
    assertEquals("MALFORMED", verdict("https://acme.ai/" + "a".repeat(3000)))
    assertEquals("MALFORMED", verdict("https://acme.ai/\u0000"))
    assertEquals("MALFORMED", verdict("https://acme‮.ai/"))
  }

  @Test
  fun `the reason is a metric tag`() {
    assertEquals("not-a-host-name", WebAddresses.Refusal.NOT_A_HOST_NAME.tag)
  }
}
