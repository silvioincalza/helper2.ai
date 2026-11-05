package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The contract allows a skip reason only on a SKIPPED source. */
class SourceStatusContractTest {

  private static String status(String outcome, String skipReason) {
    return """
    {"sourceId":"npm","name":"npm","mode":"LIVE","outcome":"%s","servedFrom":"NONE",
     "resultCount":0,"checkedAt":"2026-11-05T10:00:00Z","skipReason":%s}
    """
        .formatted(outcome, skipReason);
  }

  @Test
  void aSkippedSourceSaysWhy() {
    assertThatCode(() ->
            ContractValidator.assertMatches("SourceStatus", status("SKIPPED", "\"CIRCUIT_OPEN\"")))
        .doesNotThrowAnyException();
  }

  @Test
  void aSourceThatRanHasNoSkipReason() {
    assertThatCode(() -> ContractValidator.assertMatches("SourceStatus", status("FAILED", "null")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> ContractValidator.assertMatches("SourceStatus", status("FAILED", "\"BUSY\"")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("skipReason");
  }

  @Test
  void anUnknownReasonBreaksTheContract() {
    assertThatThrownBy(
            () -> ContractValidator.assertMatches("SourceStatus", status("SKIPPED", "\"TIRED\"")))
        .isInstanceOf(AssertionError.class);
  }
}
