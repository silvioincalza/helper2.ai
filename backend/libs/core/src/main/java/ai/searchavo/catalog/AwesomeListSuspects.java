package ai.searchavo.catalog;

import ai.searchavo.shared.ResourceType;
import java.util.List;
import java.util.UUID;

public interface AwesomeListSuspects {

  List<Suspect> suspects(int limit);

  record Suspect(
      UUID resourceId,
      String repoFullName,
      List<String> topics,
      ResourceType type,
      boolean typeCurated) {}
}
