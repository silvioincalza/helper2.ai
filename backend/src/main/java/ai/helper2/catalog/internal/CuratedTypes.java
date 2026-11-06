package ai.helper2.catalog.internal;

import ai.helper2.shared.ResourceType;
import java.util.EnumSet;
import java.util.Set;

/** What the curated source holds: the seed's products, and the tools and models an admin adds. */
public final class CuratedTypes {

  /**
   * Things a relationship points at (ADR 0064): tools and models, not writing, events or courses.
   */
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
