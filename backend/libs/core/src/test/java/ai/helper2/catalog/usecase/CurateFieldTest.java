package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.CuratedFields;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurateFieldTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-02T10:00:00Z"), ZoneOffset.UTC);

  private final UUID resource = UUID.randomUUID();
  private final UUID unknown = UUID.randomUUID();
  private final OneResource fields = new OneResource(resource);
  private final List<String> audit = new ArrayList<>();
  private final CurateField curate =
      new CurateField(fields, (by, action, target, outcome) -> audit.add(outcome), CLOCK);

  @Test
  void aValueTheFieldTakesIsCuratedAndRecorded() {
    assertThat(curate.set(resource, "pricing", " PAID ", "admin"))
        .isEqualTo(new CurateField.Outcome.Curated());
    assertThat(curate.set(resource, "description", "Writes notes.", "admin"))
        .isEqualTo(new CurateField.Outcome.Curated());
    assertThat(curate.set(resource, "publishedAt", "2026-03-03", "admin"))
        .isEqualTo(new CurateField.Outcome.Curated());

    assertThat(fields.written)
        .containsExactly("pricing=PAID", "description=Writes notes.", "publishedAt=2026-03-03");
    assertThat(audit).containsExactly("SET pricing", "SET description CURATED", "SET publishedAt");
  }

  @Test
  void aFieldAnAdminCannotSetIsUnknown() {
    assertThat(curate.set(resource, "useCase", "coding", "admin"))
        .isEqualTo(new CurateField.Outcome.UnknownField("useCase"));
    assertNothingChanged();
  }

  @Test
  void aValueTheFieldDoesNotTakeIsNotAllowed() {
    assertThat(curate.set(resource, "pricing", "CHEAP", "admin"))
        .isEqualTo(new CurateField.Outcome.ValueNotAllowed("pricing"));
    assertThat(curate.set(resource, "type", "OTHER", "admin"))
        .isEqualTo(new CurateField.Outcome.ValueNotAllowed("type"));
    assertNothingChanged();
  }

  @Test
  void aDescriptionIsOneToFourThousandCharacters() {
    CurateField.Outcome invalid = new CurateField.Outcome.InvalidDescription(4000);

    assertThat(curate.set(resource, "description", " ", "admin")).isEqualTo(invalid);
    assertThat(curate.setDescription(resource, "a".repeat(4001), true, "admin")).isEqualTo(invalid);
    assertNothingChanged();
  }

  @Test
  void aPublishedDateIsADayNotAfterTomorrow() {
    assertThat(curate.set(resource, "publishedAt", "2 March", "admin"))
        .isEqualTo(new CurateField.Outcome.NotADate());
    assertThat(curate.set(resource, "publishedAt", "2026-03-04", "admin"))
        .isEqualTo(new CurateField.Outcome.DateInTheFuture());
    assertNothingChanged();
  }

  @Test
  void aResourceTheCatalogDoesNotHaveIsNotCurated() {
    CurateField.Outcome missing = new CurateField.Outcome.NoSuchResource();

    assertThat(curate.set(unknown, "pricing", "PAID", "admin")).isEqualTo(missing);
    assertThat(curate.setDescription(unknown, "Writes notes.", false, "admin")).isEqualTo(missing);
    assertThat(curate.setPublished(unknown, LocalDate.parse("2026-03-02"), "admin"))
        .isEqualTo(missing);
    assertNothingChanged();
  }

  @Test
  void droppingAnswersHowManyAiValuesItRejected() {
    assertThat(curate.drop(resource, "license", "admin"))
        .isEqualTo(new CurateField.DropOutcome.Dropped(OneResource.AI_VALUES));
    assertThat(audit).containsExactly("DROPPED license " + OneResource.AI_VALUES);
  }

  @Test
  void onlyAFieldTheAiWritesOfAKnownResourceIsDropped() {
    assertThat(curate.drop(resource, "description", "admin"))
        .isEqualTo(new CurateField.DropOutcome.NotWrittenByAi("description"));
    assertThat(curate.drop(unknown, "license", "admin"))
        .isEqualTo(new CurateField.DropOutcome.NoSuchResource());
    assertNothingChanged();
  }

  private void assertNothingChanged() {
    assertThat(fields.written).isEmpty();
    assertThat(fields.rejected).isEmpty();
    assertThat(audit).isEmpty();
  }

  private static final class OneResource implements CuratedFields {

    static final int AI_VALUES = 2;

    final List<String> written = new ArrayList<>();
    final List<String> rejected = new ArrayList<>();
    private final UUID known;

    OneResource(UUID known) {
      this.known = known;
    }

    @Override
    public Optional<Current> current(UUID resourceId, String field) {
      return Optional.empty();
    }

    @Override
    public boolean set(UUID resourceId, String field, String column, boolean bool, String value) {
      return write(resourceId, field + "=" + value);
    }

    @Override
    public boolean setDescription(UUID resourceId, String description, String origin) {
      return write(resourceId, "description=" + description);
    }

    @Override
    public boolean setPublished(UUID resourceId, LocalDate publishedAt) {
      return write(resourceId, "publishedAt=" + publishedAt);
    }

    @Override
    public boolean exists(UUID resourceId) {
      return known.equals(resourceId);
    }

    @Override
    public int rejectAiValues(UUID resourceId, String field) {
      rejected.add(field);
      return AI_VALUES;
    }

    private boolean write(UUID resourceId, String change) {
      if (!known.equals(resourceId)) {
        return false;
      }
      written.add(change);
      return true;
    }
  }
}
