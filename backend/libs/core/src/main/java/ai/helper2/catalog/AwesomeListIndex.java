package ai.helper2.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AwesomeListIndex {
  Replaced replace(AwesomeListText list);

  record Replaced(List<UUID> written, List<UUID> removed) {
    public static final Replaced NONE = new Replaced(List.of(), List.of());

    public Set<UUID> touched() {
      Set<UUID> touched = new LinkedHashSet<>(written);
      touched.addAll(removed);
      return touched;
    }
  }
}
