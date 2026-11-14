package ai.helper2.sources.feeds;

import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record FeedItem(
    String title, URI link, @Nullable Instant published, @Nullable String summary) {}
