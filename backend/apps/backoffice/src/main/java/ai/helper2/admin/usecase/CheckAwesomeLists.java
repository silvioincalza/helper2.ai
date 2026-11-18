package ai.helper2.admin.usecase;

import ai.helper2.catalog.AwesomeListSuspects;
import ai.helper2.catalog.AwesomeListSuspects.Suspect;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.scout.AwesomeListReader;
import ai.helper2.shared.ResourceType;
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
        curate.set(s.resourceId(), "type", ResourceType.AWESOME_LIST.name(), by);
        set++;
      } else {
        notAList++;
      }
    }
    return new Result(checked, set, notAList, keptCurated, unavailable);
  }
}
