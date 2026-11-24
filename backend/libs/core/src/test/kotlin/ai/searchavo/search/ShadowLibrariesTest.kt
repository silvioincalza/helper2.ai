package ai.searchavo.search

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ShadowLibrariesTest {

  private val libraries =
    ShadowLibraries.of(
      "z-lib",
      "z-library",
      "1lib",
      "singlelogin",
      "libgen",
      "sci-hub",
      "annas-archive",
      "pdfdrive",
    )

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "z-lib.fm",
        "ka.z-lib.fm",
        "Z-LIB.FM",
        "z-library.sk",
        "1lib.sk",
        "singlelogin.re",
        "libgen.rs",
        "libgen.is",
        "sci-hub.se",
        "annas-archive.org",
        "www.pdfdrive.com",
      ]
  )
  fun `a shadow library and each of its mirrors match`(host: String) {
    assertTrue(libraries.isShadowLibrary(host))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["scribd.com", "arxiv.org", "manning.com", "github.com", "libgen", "my-libgen-notes.dev", ""]
  )
  fun `a publisher or a look-alike name does not match`(host: String) {
    assertFalse(libraries.isShadowLibrary(host))
  }

  @ParameterizedTest
  @CsvSource(
    "https://libgen.is/book, true",
    "https://user@ka.libgen.is:8443/x, true",
    "https://my_mirror.libgen.is/x, true",
    "https://arxiv.org/abs/1, false",
  )
  fun `an address is hidden when its host is listed or cannot be read`(
    address: String,
    hidden: Boolean,
  ) {
    assertEquals(hidden, libraries.hides(java.net.URI.create(address)))
  }

  @Test
  fun `a list read from the store holds what the store holds after each reload`() {
    val names = mutableSetOf("mirror-one")
    val store =
      object : ShadowLibraryStore {
        override fun all(): List<ShadowLibrary> = emptyList()

        override fun names(): Set<String> = names.toSet()

        override fun add(library: ShadowLibrary): Boolean = false

        override fun remove(name: String): ShadowLibrary? = null
      }
    val read = ShadowLibraries.readFrom(store)

    assertTrue(read.isShadowLibrary("libgen.rs"), "before the first read: the names seeded")
    assertFalse(read.isShadowLibrary("mirror-one.fm"))
    read.reload()
    assertTrue(read.isShadowLibrary("mirror-one.fm"))
    assertFalse(read.isShadowLibrary("libgen.rs"))
    names += "mirror-two"
    assertFalse(read.isShadowLibrary("mirror-two.se"))
    read.reload()
    assertTrue(read.isShadowLibrary("mirror-two.se"))
  }

  @ParameterizedTest
  @CsvSource(
    "' LibGen ', libgen",
    "z-lib, z-lib",
    "1lib, 1lib",
    "annas-archive, annas-archive",
  )
  fun `a name is kept in lower case without spaces`(raw: String, name: String) {
    assertEquals(ShadowLibraryNames.Checked.Valid(name), ShadowLibraryNames.check(raw))
  }

  @ParameterizedTest
  @CsvSource(
    "'', EMPTY",
    "'   ', EMPTY",
    "libgen.rs, NOT_A_NAME",
    "https://libgen.rs, NOT_A_NAME",
    "lib gen, NOT_A_NAME",
    "-libgen, NOT_A_NAME",
    "libgen-, NOT_A_NAME",
    "lib_gen, NOT_A_NAME",
    "ab, TOO_SHORT",
    "www, TOO_COMMON",
    "com, TOO_COMMON",
    "blog, TOO_COMMON",
  )
  fun `a name that is not one host label, or that would match too many sites, is refused`(
    raw: String,
    problem: ShadowLibraryNames.Problem,
  ) {
    assertEquals(ShadowLibraryNames.Checked.Invalid(problem), ShadowLibraryNames.check(raw))
  }

  @Test
  fun `a name longer than a host label is refused`() {
    assertEquals(
      ShadowLibraryNames.Checked.Invalid(ShadowLibraryNames.Problem.NOT_A_NAME),
      ShadowLibraryNames.check("a".repeat(ShadowLibraryNames.MAX_LENGTH + 1)),
    )
  }
}
