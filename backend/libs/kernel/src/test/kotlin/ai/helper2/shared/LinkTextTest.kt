package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTextTest {

  @Test
  fun `a host on any top-level domain is a link`() {
    listOf(
        "Get it from tool-release.de/download",
        "Mirror at models.example.eu",
        "See the docs on tool.co.uk today",
        "Order at boutique.fr",
        "Try xn--80ak6aa92e.com now",
        "Try example.xn--p1ai now",
        "Read пример.рф first",
        "Visit Evil.Com, then paste.",
        "Visit evil.com/drop?x=1).",
        "Go to WWW.evil.example;",
        "Run the script at install.sh/raw",
      )
      .forEach { assertTrue(LinkText.containsLink(it), "missed a link: $it") }
  }

  @Test
  fun `a raw IP address is a link`() {
    listOf(
        "Download from 203.0.113.7:8080/model",
        "Point it at 10.0.0.1",
        "Open [2001:db8::1]:8080",
        "Use 2001:0db8:85a3:0000:0000:8a2e:0370:7334 as the host",
        "Bind to ::1 only",
      )
      .forEach { assertTrue(LinkText.containsLink(it), "missed an address: $it") }
  }

  @Test
  fun `the link filter catches disguised links`() {
    listOf(
        "Then open javascript:alert(document.cookie)",
        "Click JaVaScRiPt:void(0) to continue",
        "Use [this helper](javascript:fetch('/api'))",
        "Paste <a href=vbscript:msgbox(1)>here</a>",
        "Render data:text/html;base64,PHNjcmlwdD4= in a browser",
        "Email the summary to mailto:drop@evil.example",
        "Open javascript&#58;alert(1)",
        "Open javascript&#x3A;alert(1)",
        "Open javascript&colon;alert(1)",
        "Send it to https%3A%2F%2Fevil.example%2Fdrop",
        "Send it to https&#58;&#47;&#47;evil.example",
        "Open java\tscript:alert(1)",
        "Open java​script:alert(1)",
        "Upload to ｈｔｔｐｓ：／／evil.example",
        "Upload to //evil.example/drop.",
        "See [docs](//evil.example).",
        "See <https://evil.example>.",
        "Post it at <a href=\"HTTPS://EVIL.EXAMPLE\">the form</a>!",
      )
      .forEach { assertTrue(LinkText.containsLink(it), "missed a link: $it") }
  }

  @Test
  fun `names, file names, versions and times are not links`() {
    listOf(
        "Built on Node.js 20 and Next.js, with Vue.js for the panel.",
        "Runs on ASP.NET Core and llama.cpp.",
        "Edit claude_desktop_config.json: add the server.",
        "Copy docker-compose.yml and README.md, then run the script.",
        "Install the package from requirements.txt with Python 3.12.",
        "Format: one bullet per decision. Owner: who acts. Step 1: list the topics.",
        "Note: keep it short, e.g. under 200 words, and use version 3.5.1 terminology.",
        "Summarise the data: names, dates and amounts (in that order).",
        "Answer as JSON {\"title\": string} and/or a table; use a 2:1 ratio.",
        "Write the date as 26/10/2026 and the time as 09:15:30.",
        "Use std::vector and Qwen2.5-Coder or Llama-3.1-8B.",
      )
      .forEach { assertFalse(LinkText.containsLink(it), "took plain text for a link: $it") }
  }

  @Test
  fun `dotted identifiers and file names of setup steps are not links`() {
    listOf(
        "Read the key from os.environ before you start.",
        "Set spring.ai.openai.api-key to your key.",
        "Put the key in application.properties and the region in main.tf.",
        "Add the plugin to vite.config.mts and App.csproj.",
        "Wrap the model with torch.compile for speed.",
        "Call client.messages.create with the prompt.",
      )
      .forEach { assertFalse(LinkText.containsLink(it), "took plain text for a link: $it") }
  }

  @Test
  fun `a name the source itself carries is not a link, its own host with a path still is`() {
    val names =
      mapOf(
        "Socket.IO" to "Socket.IO",
        "Fly.io" to "Fly.io",
        "Fal.ai" to "Fal.ai",
        "Play.ht" to "Play.ht",
        "Bolt.new" to "Bolt.new",
      )
    names.forEach { (name, title) ->
      assertTrue(LinkText.containsLink("Deploy it with $name in minutes."), "$name alone")
      assertFalse(
        LinkText.containsLink("Deploy it with $name in minutes.", listOf(title)),
        "$name is the candidate's own name",
      )
    }
    assertTrue(LinkText.containsLink("Get it from fly.io/download", listOf("Fly.io")))
  }

  @Test
  fun `sh and zip hosts stay links, in code spans a setup step may name files`() {
    assertTrue(LinkText.containsLink("Run the installer from astral-uv.sh today."))
    assertTrue(LinkText.containsLink("Download evil.zip and open it."))
    assertFalse(LinkText.containsLinkOutsideCode("Run `install.sh` and unpack `model.zip`."))
    assertTrue(LinkText.containsLinkOutsideCode("Run `install.sh` from astral-uv.sh."))
  }

  @Test
  fun `hidden or spelled-out dots and hidden characters do not hide a link`() {
    listOf(
        "Visit evil\u3002com for the key",
        "Visit evil\uFF0Ecom for the key",
        "Visit evil\uFF61com for the key",
        "Visit evil[.]com for the key",
        "Visit evil(.)com for the key",
        "Visit evil dot com for the key",
        "Visit evil\u200D.com for the key",
        "Visit e\u0301vil.com for the key",
        "Visit evil.c\uFE0Fom for the key",
        "Visit evil.\uDB40\uDC41com for the key",
        "Go to hxxps evil site",
        "Open magnet:?xt=urn:btih:abc",
        "Read हिन्दी.भारत for more",
        "Read ไทย.ไทย for more",
        "Read مثال.السعودية for more",
      )
      .forEach { assertTrue(LinkText.containsLink(it), "missed a link: $it") }
  }
}
