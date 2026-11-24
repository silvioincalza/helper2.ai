package ai.searchavo.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SameOriginWritesTest {

  @Test
  void theOriginMustNameTheHostTheRequestWentTo() {
    assertThat(SameOriginWrites.fromSameHost("https://admin.searchavo.ai", "admin.searchavo.ai"))
        .isTrue();
    assertThat(SameOriginWrites.fromSameHost("https://ADMIN.searchavo.ai", "admin.searchavo.ai"))
        .isTrue();
    assertThat(SameOriginWrites.fromSameHost("http://localhost:8082", "localhost:8082")).isTrue();
  }

  @Test
  void anotherHostOfTheSameSiteIsRefused() {
    assertThat(SameOriginWrites.fromSameHost("https://searchavo.ai", "admin.searchavo.ai"))
        .isFalse();
    assertThat(SameOriginWrites.fromSameHost("https://x.admin.searchavo.ai", "admin.searchavo.ai"))
        .isFalse();
    assertThat(
            SameOriginWrites.fromSameHost("https://admin.searchavo.ai:444", "admin.searchavo.ai"))
        .isFalse();
  }

  @Test
  void noOriginAnOpaqueOneOrGarbageIsRefused() {
    assertThat(SameOriginWrites.fromSameHost(null, "admin.searchavo.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("", "admin.searchavo.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("null", "admin.searchavo.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("http://[bad", "admin.searchavo.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("https://admin.searchavo.ai", null)).isFalse();
  }
}
