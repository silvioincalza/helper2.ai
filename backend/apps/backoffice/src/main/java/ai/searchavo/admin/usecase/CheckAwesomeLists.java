package ai.searchavo.admin.usecase;

import ai.searchavo.catalog.AwesomeListSuspects;
import ai.searchavo.catalog.AwesomeListSuspects.Suspect;
import ai.searchavo.catalog.usecase.CurateField;
import ai.searchavo.scout.AwesomeListReader;
import ai.searchavo.shared.ResourceType;
import java.util.Optional;

public final class CheckAwesomeLists {

  public static final int MAX_CHECKED = 2_000;

  private final AwesomeListSuspects suspects;
  private final AwesomeListReader reader;
  private final CurateField curate;

  public CheckAwesomeLists(
      AwesomeListSuspects suspects, AwesomeListReader reader, CurateField curate) {
    this.suspects = suspects;
    this.reader = reader;
    this.curate = curate;
  }

  public record Result(int checked, int set, int notAList, int keptCurated, int unavailable) {}

  public Result run(String by) {
    int checked = 0;
    int set = 0;
    int notAList = 0;
    int keptCurated = 0;
    int unavailable = 0;
    for (Suspect s : suspects.suspects(MAX_CHECKED)) {
      checked++;
      if (s.typeCurated()) {
        keptCurated++;
        continue;
      }
      Optional<Boolean> list = reader.isAwesomeList(s.repoFullName(), s.topics());
      if (list.isEmpty()) {
        unavailable++;
      } else if (list.get()) {
        set += setAsAwesomeList(s, by) ? 1 : 0;
      } else {
        notAList++;
      }
    }
    return new Result(checked, set, notAList, keptCurated, unavailable);
  }

  private boolean setAsAwesomeList(Suspect suspect, String by) {
    CurateField.Outcome outcome =
        curate.set(suspect.resourceId(), "type", ResourceType.AWESOME_LIST.name(), by);
    return switch (outcome) {
      case CurateField.Outcome.Curated curated -> true;
      case CurateField.Outcome.NoSuchResource gone -> false;
      case CurateField.Outcome.ValueRefused refused ->
          throw new IllegalStateException("the catalog refused the type: " + outcome);
    };
  }
}
