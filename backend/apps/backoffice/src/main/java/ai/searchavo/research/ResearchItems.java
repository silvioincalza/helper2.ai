package ai.searchavo.research;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchItems {

  Optional<ResearchSubject> subject(UUID itemId);

  List<UUID> openNotResearched(int limit);

  int openResearchedDone();
}
