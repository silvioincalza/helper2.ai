package ai.helper2.catalog.usecase;

import ai.helper2.catalog.RelationshipTargets;

public class LinkKnownRelationshipTargets {

  public static final String BY = "scout";

  public static final String NOTE = "The target is now in the catalog: linked by its name";

  private final RelationshipTargets targets;

  public LinkKnownRelationshipTargets(RelationshipTargets targets) {
    this.targets = targets;
  }

  public static final String VENDOR_NOTE = "The target is a known vendor";

  public static final String SERVICE_NOTE = "The target is a known service";

  public static final String TOOL_NOTE = "The target is a known tool";

  public int link() {
    int vendors =
        targets.confirmOpenKnownTargetCandidates(BY, VENDOR_NOTE, SERVICE_NOTE, TOOL_NOTE);
    return vendors + targets.linkOpenCandidates(BY, NOTE);
  }
}
