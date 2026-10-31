package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuides;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** "Guide me", step by step: checked steps from a resource's own documents. */
@Service
public class GetToolGuide {

    private static final Logger log = LoggerFactory.getLogger(GetToolGuide.class);

    /** The guide and the title of the resource it is for. */
    public record Guided(String title, ToolGuideAnswer answer) {
    }

    private final GetResourceDetail details;
    private final ToolGuides toolGuides;

    public GetToolGuide(GetResourceDetail details, ToolGuides toolGuides) {
        this.details = details;
        this.toolGuides = toolGuides;
    }

    /** Empty when the resource does not exist. */
    public Optional<Guided> guide(UUID resourceId) {
        return details.find(resourceId).map(d -> guide(resourceId, d));
    }

    private Guided guide(UUID resourceId, ResourceDetail d) {
        ToolGuideAnswer a = toolGuides.answer(resourceId.toString(), ResourceToolRefs.ref(d));
        if (a.getModel() != null) {
            log.info("tool guide: status={} kept={} rejectedBy={} documents={}", a.getStatus(),
                a.getSections().stream().mapToInt(s -> s.getSteps().size()).sum(), a.getRejectedBy(), a.getMaterials().size());
        }
        return new Guided(d.card().getTitle(), a);
    }
}
