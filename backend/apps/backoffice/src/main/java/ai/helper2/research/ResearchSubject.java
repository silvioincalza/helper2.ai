package ai.helper2.research;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ResearchSubject(
    UUID itemId,
    String kind,
    String url,
    String title,
    String type,
    List<String> tags,
    @Nullable String snippet) {}
