package ai.helper2.sources.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShadowLibrariesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
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
        "www.pdfdrive.com"
      })
  void aShadowLibraryAndEachOfItsMirrorsMatch(String host) {
    assertThat(ShadowLibraries.isShadowLibrary(host)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "scribd.com",
        "arxiv.org",
        "manning.com",
        "github.com",
        "libgen",
        "my-libgen-notes.dev",
        ""
      })
  void aPublisherOrALookAlikeNameDoesNotMatch(String host) {
    assertThat(ShadowLibraries.isShadowLibrary(host)).isFalse();
  }
}
