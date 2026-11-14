package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.setup.SetupGuide;
import ai.helper2.setup.SetupGuides;
import java.util.Optional;
import java.util.UUID;

public class GetResourceSetup {

  private final GetResourceDetail details;
  private final SetupGuides guides;

  public GetResourceSetup(GetResourceDetail details, SetupGuides guides) {
    this.details = details;
    this.guides = guides;
  }

  public Optional<SetupGuide> setup(UUID resourceId) {
    return details.find(resourceId).map(this::guide);
  }

  private SetupGuide guide(ResourceDetail d) {
    return guides.build(
        d.card().getTitle(), ResourceToolRefs.registryName(d), ResourceToolRefs.ref(d));
  }
}
