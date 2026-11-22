package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** UC-013 BR-004: what the value before, the value after and the reason of an entry hold. */
class AuditTrailChangeTest {

  static final String FULL = "a".repeat(AuditTrail.Change.MAX_LENGTH);

  @Test
  void uc013Br004ATextOf1000CharactersFitsEachOfTheThree() {
    assertThat(AuditTrail.Change.MAX_LENGTH).isEqualTo(1000);
    assertThatCode(() -> new AuditTrail.Change(FULL, FULL, FULL)).doesNotThrowAnyException();
    assertThatCode(() -> new AuditTrail.Change(null, null, null)).doesNotThrowAnyException();
  }

  @Test
  void uc013Br004ALongerTextFailsWithWordsThatNameIt() {
    assertThatThrownBy(() -> new AuditTrail.Change(FULL + "a", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the value before of an audit entry holds at most 1000 characters, not 1001");
    assertThatThrownBy(() -> new AuditTrail.Change(null, FULL + "a", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the value after of an audit entry holds at most 1000 characters, not 1001");
    assertThatThrownBy(() -> new AuditTrail.Change(null, null, FULL + "a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the reason of an audit entry holds at most 1000 characters, not 1001");
  }

  // The database counts characters, not UTF-16 units: a character outside the basic plane is one.
  @Test
  void uc013Br004ACharacterOutsideTheBasicPlaneCountsOnceAsInTheDatabase() {
    assertThatCode(() ->
            new AuditTrail.Change("\uD83D\uDE00".repeat(AuditTrail.Change.MAX_LENGTH), null, null))
        .doesNotThrowAnyException();
  }
}
