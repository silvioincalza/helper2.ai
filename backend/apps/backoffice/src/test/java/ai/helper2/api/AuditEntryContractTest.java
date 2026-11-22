package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.AuditTrail;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** UC-013 BR-004: the API states the length the audit trail holds, from the same number. */
class AuditEntryContractTest {

  final JsonNode entry = ContractValidator.schema("AuditEntry").get("properties");

  @ParameterizedTest
  @ValueSource(strings = {"before", "after", "reason"})
  void uc013Br004TheApiStatesTheLengthOfAnAuditText(String field) {
    assertThat(entry.get(field).get("maxLength").asInt()).isEqualTo(AuditTrail.Change.MAX_LENGTH);
    assertThat(entry.get(field).get("description").asString())
        .contains("at most " + AuditTrail.Change.MAX_LENGTH + " characters");
  }
}
