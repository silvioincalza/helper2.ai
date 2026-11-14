package ai.helper2.catalog;

import ai.helper2.shared.ResourceType;
import java.util.EnumSet;
import java.util.Set;

public final class CuratedTypes {
  public static final Set<ResourceType> TYPES =
      Set.copyOf(
          EnumSet.of(
              ResourceType.PRODUCT,
              ResourceType.MODEL,
              ResourceType.AGENT,
              ResourceType.PROMPT,
              ResourceType.SKILL,
              ResourceType.MCP_SERVER,
              ResourceType.LIBRARY,
              ResourceType.DATASET));

  private CuratedTypes() {}
}
