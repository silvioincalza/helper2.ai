package ai.helper2.search.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.CachedHits;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SourceHitCodecTest {

    private final SourceHitCodec codec = new SourceHitCodec(JsonMapper.builder().build());
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void anEventKeepsItsDaysPlaceKindAndFormatThroughTheCache() {
        SourceHit event = new SourceHit("tavily", "x", URI.create("https://conf.example.org/2026"),
            CanonicalUrl.of("https://conf.example.org/2026"), null, "Conf 2026", "An AI conference",
            ValueOrigin.SOURCE_PROVIDED, ResourceType.EVENT, Audience.BOTH, null, Set.of(), Set.of("conference"), null, null,
            Set.of(), null, DateFact.fromSource(Instant.parse("2026-09-01T00:00:00Z"), "tavily", now), DateFact.unknown(),
            List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 42, "tavily", now)), now, 3,
            DateFact.scheduledDay(LocalDate.parse("2026-12-06"), "tavily"), DateFact.scheduledDay(LocalDate.parse("2026-12-12"), "tavily"),
            "Sydney, Australia", EventKind.HACKATHON, EventFormat.HYBRID);

        CachedHits back = codec.decode(codec.encode(new CachedHits(List.of(event), now)));

        assertThat(back).isNotNull();
        assertThat(back.getHits()).containsExactly(event);
    }

    @Test
    void entriesOfAnotherVersionAreAMiss() {
        String encoded = codec.encode(new CachedHits(List.of(), now));
        assertThat(codec.decode(encoded.replace("\"v\":" + SourceHitCodec.VERSION, "\"v\":" + (SourceHitCodec.VERSION + 1)))).isNull();
    }
}
