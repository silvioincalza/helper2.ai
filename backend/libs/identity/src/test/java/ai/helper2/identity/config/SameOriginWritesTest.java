package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SameOriginWritesTest {

  @Test
  void theOriginMustNameTheHostTheRequestWentTo() {
    assertThat(SameOriginWrites.fromSameHost("https://admin.helper2.ai", "admin.helper2.ai"))
        .isTrue();
    assertThat(SameOriginWrites.fromSameHost("https://ADMIN.helper2.ai", "admin.helper2.ai"))
        .isTrue();
    assertThat(SameOriginWrites.fromSameHost("http://localhost:8082", "localhost:8082")).isTrue();
  }

  @Test
  void anotherHostOfTheSameSiteIsRefused() {
    assertThat(SameOriginWrites.fromSameHost("https://helper2.ai", "admin.helper2.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("https://x.admin.helper2.ai", "admin.helper2.ai"))
        .isFalse();
    assertThat(SameOriginWrites.fromSameHost("https://admin.helper2.ai:444", "admin.helper2.ai"))
        .isFalse();
  }

  @Test
  void noOriginAnOpaqueOneOrGarbageIsRefused() {
    assertThat(SameOriginWrites.fromSameHost(null, "admin.helper2.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("", "admin.helper2.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("null", "admin.helper2.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("http://[bad", "admin.helper2.ai")).isFalse();
    assertThat(SameOriginWrites.fromSameHost("https://admin.helper2.ai", null)).isFalse();
  }
}
