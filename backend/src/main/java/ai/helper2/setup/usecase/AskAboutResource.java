package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuides;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Checked steps from a resource's own documents, or the answer to one question about it. */
@Service
public class AskAboutResource {

    private static final Logger log = LoggerFactory.getLogger(AskAboutResource.class);

    /** The answer and the title of the resource it is about. */
    public record Answered(String title, ToolGuideAnswer answer) {
    }

    private final GetResourceDetail details;
    private final ToolGuides toolGuides;

    public AskAboutResource(GetResourceDetail details, ToolGuides toolGuides) {
        this.details = details;
        this.toolGuides = toolGuides;
    }

    /** Empty when the resource does not exist. Without a question, the step-by-step guide. */
    public Optional<Answered> ask(UUID resourceId, @Nullable String question) {
        return details.find(resourceId).map(d -> answer(resourceId, d, question));
    }

    private Answered answer(UUID resourceId, ResourceDetail d, @Nullable String question) {
        ToolGuideAnswer a = toolGuides.answer(resourceId.toString(), ResourceToolRefs.ref(d), question);
        if (a.getModel() != null) {
            log.info("tool guide: status={} kept={} rejectedBy={} documents={}", a.getStatus(),
                a.getSections().stream().mapToInt(s -> s.getSteps().size()).sum(), a.getRejectedBy(), a.getMaterials().size());
        }
        return new Answered(d.card().getTitle(), a);
    }
}
