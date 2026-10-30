package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.setup.SetupGuide;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.SetupUnavailable;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** "Guide me": how to run a resource and connect it to each AI client, from its registry entry or README. */
@Service
public class GetResourceSetup {

    private final GetResourceDetail details;
    private final SetupGuides guides;

    public GetResourceSetup(GetResourceDetail details, SetupGuides guides) {
        this.details = details;
        this.guides = guides;
    }

    /** Empty when the resource does not exist; {@link SetupUnavailable} when its setup data cannot be read now. */
    public Optional<SetupGuide> setup(UUID resourceId) {
        return details.find(resourceId).map(this::guide);
    }

    private SetupGuide guide(ResourceDetail d) {
        return guides.build(d.card().getTitle(), ResourceToolRefs.registryName(d), ResourceToolRefs.ref(d));
    }
}
