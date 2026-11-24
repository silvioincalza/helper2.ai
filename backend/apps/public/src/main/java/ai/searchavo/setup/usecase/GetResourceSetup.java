package ai.searchavo.setup.usecase;

import ai.searchavo.catalog.ResourceDetail;
import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.setup.SetupGuide;
import ai.searchavo.setup.SetupGuides;
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
