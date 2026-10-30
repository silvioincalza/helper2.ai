package ai.helper2.sources;

import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/** Shared test data of the source adapters. */
public final class SourceFixtures {

    /** The fixed "now" of every source adapter test. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);

    private SourceFixtures() {
    }

    /** A minimal hit, for tests of the plumbing around the mappers. */
    public static SourceHit hit(String id, int rank) {
        String url = "https://example.org/" + id;
        return new SourceHit("test-source", id, URI.create(url), CanonicalUrl.of(url), null, id, null,
            ValueOrigin.SOURCE_PROVIDED, ResourceType.LIBRARY, Audience.BUILD_WITH_AI, null, Set.of(),
            Set.of(), null, null, Set.of(), null, DateFact.unknown(), DateFact.unknown(), List.of(), CLOCK.instant(), rank);
    }
}
