package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.KnownTargetCandidates.Proposal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KnownTargetProposalJsonTest {

  private final KnownTargetProposalJson json =
      new KnownTargetProposalJson(JsonMapper.builder().build());

  @Test
  void aStoredProposalIsReadWithItsExamplesAndItsOtherNames() {
    assertThat(
            json.read(
                """
                {"key": "acme", "name": "Acme", "kind": "SERVICE", "rule": "OWNER", "count": 6,
                 "edited": true, "otherNames": ["Acme AI", 7],
                 "examples": [{"id": "1", "title": "acme/agent"}, {"id": "2"}, "x"]}
                """))
        .contains(
            new Proposal(
                "Acme",
                KnownTarget.Kind.SERVICE,
                "OWNER",
                6,
                List.of("acme/agent"),
                List.of("Acme AI"),
                true));
  }

  @Test
  void aProposalAsTheRuleFirstWroteItHasNoEditAndNoOtherName() {
    assertThat(
            json.read(
                "{\"key\":\"acme\",\"name\":\"Acme\",\"kind\":\"VENDOR\",\"rule\":\"OWNER\",\"count\":3}"))
        .contains(
            new Proposal("Acme", KnownTarget.Kind.VENDOR, "OWNER", 3, List.of(), List.of(), false));
  }

  @Test
  void detailsThatDoNotReadAsAProposalAreNotOne() {
    assertThat(json.read(null)).isEmpty();
    assertThat(json.read("")).isEmpty();
    assertThat(json.read("not json")).isEmpty();
    assertThat(json.read("[1]")).isEmpty();
    assertThat(json.read("{\"name\":\"Acme\",\"kind\":\"VENDOR\",\"rule\":\"OWNER\"}")).isEmpty();
    assertThat(json.read("{\"name\":\"Acme\",\"kind\":\"PERSON\",\"rule\":\"OWNER\",\"count\":3}"))
        .isEmpty();
    assertThat(json.read("{\"name\":\"Acme\",\"kind\":\"VENDOR\",\"rule\":\"STARS\",\"count\":3}"))
        .isEmpty();
  }
}
