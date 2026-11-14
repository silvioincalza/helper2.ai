package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KeptAttributesTest {

  @Test
  void onlyTheSubjectAndTheNameAreKept() {
    Map<String, Object> reply =
        Map.of(
            "id", 4242L,
            "login", "octocat",
            "name", "The Octocat",
            "email", "octo@example.com",
            "bio", "a bio",
            "location", "Zurich",
            "company", "ACME",
            "avatar_url", "https://avatars.example/4242");

    assertThat(WebSecurity.keptAttributes(reply, "id"))
        .containsExactlyInAnyOrderEntriesOf(Map.of("id", 4242L, "name", "The Octocat"));
  }

  @Test
  void withoutANameTheLoginIsTheName() {
    assertThat(WebSecurity.keptAttributes(Map.of("id", 1L, "login", "octocat"), "id"))
        .containsExactlyInAnyOrderEntriesOf(Map.of("id", 1L, "name", "octocat"));
  }

  @Test
  void withNeitherOnlyTheSubjectIsKept() {
    assertThat(WebSecurity.keptAttributes(Map.of("id", 1L), "id"))
        .containsExactlyInAnyOrderEntriesOf(Map.of("id", 1L));
  }
}
