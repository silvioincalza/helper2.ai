package ai.helper2.catalog;

import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.UUID;

public interface AwesomeListSuspects {

  List<Suspect> suspects(int limit);

  record Suspect(UUID resourceId, String repoFullName, ResourceType type, boolean typeCurated) {}
}
