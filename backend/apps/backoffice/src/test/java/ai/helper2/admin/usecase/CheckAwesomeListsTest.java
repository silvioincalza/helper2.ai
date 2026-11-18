package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.AwesomeListSuspects.Suspect;
import ai.helper2.catalog.CuratedFields;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.shared.ResourceType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckAwesomeListsTest {

  private final UUID gone = UUID.randomUUID();
  private final UUID kept = UUID.randomUUID();
  private final List<String> audit = new ArrayList<>();
  private final CurateField curate =
      new CurateField(
          new ResourcesBut(gone),
          (by, action, target, outcome) -> audit.add(action + " " + target + " " + outcome),
          Clock.systemUTC());

  @Test
  void aSuspectWhoseResourceIsGoneIsNotSetAndTheCheckGoesOn() {
    CheckAwesomeLists check =
        new CheckAwesomeLists(
            limit -> List.of(suspect(gone), suspect(kept)),
            (repo, topics) -> Optional.of(true),
            curate);

    assertThat(check.run("admin")).isEqualTo(new CheckAwesomeLists.Result(2, 1, 0, 0, 0));
    assertThat(audit).containsExactly("SET_FIELD " + kept + " SET type");
  }

  private static Suspect suspect(UUID resource) {
    return new Suspect(resource, "acme/awesome", List.of(), ResourceType.LIBRARY, false);
  }

  private record ResourcesBut(UUID missing) implements CuratedFields {

    @Override
    public Optional<Current> current(UUID resourceId, String field) {
      return Optional.empty();
    }

    @Override
    public boolean set(UUID resourceId, String field, String column, boolean bool, String value) {
      return !resourceId.equals(missing);
    }

    @Override
    public boolean setDescription(UUID resourceId, String description, String origin) {
      return !resourceId.equals(missing);
    }

    @Override
    public boolean setPublished(UUID resourceId, LocalDate publishedAt) {
      return !resourceId.equals(missing);
    }

    @Override
    public boolean exists(UUID resourceId) {
      return !resourceId.equals(missing);
    }

    @Override
    public int rejectAiValues(UUID resourceId, String field) {
      return 0;
    }
  }
}
