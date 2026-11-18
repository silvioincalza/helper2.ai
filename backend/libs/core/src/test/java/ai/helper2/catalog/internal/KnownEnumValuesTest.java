package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.shared.ResourceType;
import org.junit.jupiter.api.Test;

class KnownEnumValuesTest {

  @Test
  void aKindANewerReleaseWroteReadsAsTheFallback() {
    assertThat(ResourceRows.known(KnownTarget.Kind.class, "PLATFORM", KnownTarget.Kind.TOOL))
        .isEqualTo(KnownTarget.Kind.TOOL);
    assertThat(
            ResourceRows.known(KnownTarget.Origin.class, "IMPORTED", KnownTarget.Origin.DISCOVERED))
        .isEqualTo(KnownTarget.Origin.DISCOVERED);
    assertThat(ResourceRows.known(ResourceType.class, "SOMETHING_NEW", ResourceType.OTHER))
        .isEqualTo(ResourceType.OTHER);
  }

  @Test
  void aKnownValueReadsAsItself() {
    assertThat(ResourceRows.known(KnownTarget.Kind.class, "VENDOR", KnownTarget.Kind.TOOL))
        .isEqualTo(KnownTarget.Kind.VENDOR);
  }
}
