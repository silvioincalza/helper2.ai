package ai.helper2.sources;

import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;

/**
 * AI conferences from huggingface/ai-deadlines (MIT): one YAML file per conference with each edition's
 * start and end day, city, country and official link. One record per conference (the file name), rolled
 * forward to its next edition that has not ended, so a past edition never lingers with stale dates.
 * Dates are the days the file states (DAY precision); an edition without a parseable start is skipped.
 * One GitHub contents call lists the files, then each raw file is read once per pass.
 * Repository: https://github.com/huggingface/ai-deadlines
 *
 * The raw files are read only from {@link #RAW_HOST}, without the GitHub token: download_url is data from
 * the API response, and the token must never go to a host it names. A file that cannot be read or parsed
 * is skipped (logged and counted) and the pass goes on with the others.
 */
public class AiDeadlinesCollector implements Collector {

    static final String ID = "ai-deadlines";
    static final String CONFERENCES_PATH = "/repos/huggingface/ai-deadlines/contents/src/data/conferences";
    static final URI RAW_HOST = URI.create("https://raw.githubusercontent.com");

    private static final Logger log = LoggerFactory.getLogger(AiDeadlinesCollector.class);

    private final RestClient http;
    private final RestClient raw;
    private final URI rawHost;
    private final Clock clock;
    private final RunInterval interval;
    private final SourceDescriptor descriptor;

    public AiDeadlinesCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, RunInterval interval) {
        this(settings, builder, clock, interval, RAW_HOST);
    }

    /** [rawHost]: scheme, host and port download URLs must have (tests point it at a fixture server). */
    AiDeadlinesCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, RunInterval interval, URI rawHost) {
        this.clock = clock;
        this.interval = interval;
        this.rawHost = rawHost;
        // Cloned before the GitHub client adds its base URL and Authorization header to the builder.
        this.raw = builder.clone().build();
        this.http = HttpClients.gitHubClient(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "AI conference dates (huggingface/ai-deadlines)", SourceMode.INDEXED,
            URI.create("https://github.com/huggingface/ai-deadlines"), URI.create("https://github.com/huggingface/ai-deadlines/blob/main/LICENSE"),
            "Conference dates and venues from huggingface/ai-deadlines (MIT License)", EnumSet.of(ResourceType.EVENT),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 40);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
        Instant startedAt = clock.instant();
        if (!interval.isDue(checkpoint, startedAt)) {
            return CollectedBatch.notDue();
        }
        LocalDate today = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
        JsonNode files = HttpClients.getJson(() -> http.get().uri(CONFERENCES_PATH).retrieve().body(String.class), clock);
        List<SourceHit> hits = new ArrayList<>();
        int rank = 0;
        int skipped = 0;
        for (JsonNode f : files) {
            String name = HttpClients.text(f, "name");
            String download = HttpClients.text(f, "download_url");
            if (name == null || download == null || !name.endsWith(".yml") || hits.size() >= maxItems) {
                continue;
            }
            Optional<URI> url = allowed(download);
            if (url.isEmpty()) {
                skipped++;
                log.warn("ai-deadlines: {} skipped, download URL is not on {}", name, rawHost.getHost());
                continue;
            }
            try {
                String yaml = HttpClients.getText(() -> raw.get().uri(url.get()).retrieve().body(String.class), clock);
                Optional<SourceHit> hit = nextEdition(name.substring(0, name.length() - 4), yaml, today, startedAt, rank);
                if (hit.isPresent()) {
                    hits.add(hit.get());
                    rank++;
                }
            } catch (RuntimeException e) {
                skipped++;
                log.warn("ai-deadlines: {} skipped: {}", name, e.getClass().getSimpleName());
            }
        }
        if (skipped > 0) {
            log.warn("ai-deadlines: {} of the conference files were skipped in this pass", skipped);
        }
        return new CollectedBatch(hits, null, startedAt.toString());
    }

    /** [download] when it is on the raw host (same scheme, host and port); anything else is never fetched. */
    Optional<URI> allowed(String download) {
        try {
            URI u = new URI(download.strip());
            boolean sameHost = rawHost.getScheme().equalsIgnoreCase(u.getScheme()) && rawHost.getHost().equalsIgnoreCase(u.getHost())
                && rawHost.getPort() == u.getPort() && u.getUserInfo() == null;
            return sameHost ? Optional.of(u) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** The edition that ends (or, without an end, starts) soonest on or after [today]; empty when none. */
    static Optional<SourceHit> nextEdition(String conference, String yaml, LocalDate today, Instant now, int position) {
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(parsed instanceof List<?> editions)) {
            return Optional.empty();
        }
        return editions.stream()
            .filter(Map.class::isInstance).map(e -> (Map<?, ?>) e)
            .map(e -> new Edition(e, day(e.get("start")), day(e.get("end"))))
            .filter(e -> e.start != null && !Objects.requireNonNullElse(e.end, e.start).isBefore(today))
            .min(Comparator.comparing(Edition::start))
            .flatMap(e -> toHit(conference, e, now, position));
    }

    private record Edition(Map<?, ?> raw, LocalDate start, LocalDate end) {
        String text(String key) {
            Object v = raw.get(key);
            return v == null || v.toString().isBlank() ? null : v.toString().strip();
        }
    }

    private static Optional<SourceHit> toHit(String conference, Edition e, Instant now, int position) {
        String link = e.text("link");
        CanonicalUrl canonical = link == null ? null : CanonicalUrl.of(link);
        String title = e.text("title");
        if (canonical == null || title == null) {
            return Optional.empty();
        }
        String year = e.text("year");
        String location = Stream.of(e.text("city"), e.text("country")).filter(Objects::nonNull).collect(Collectors.joining(", "));
        String description = Stream.of(e.text("full_name"), e.text("venue"))
            .filter(Objects::nonNull).collect(Collectors.joining(". "));
        return Optional.of(new SourceHit(ID, conference, HttpClients.link(link, canonical), canonical, null,
            year == null || title.contains(year) ? title : title + " " + year,
            description.isEmpty() ? null : description, ValueOrigin.SOURCE_PROVIDED, ResourceType.EVENT, Audience.BUILD_WITH_AI,
            null, Set.of(), Set.of("conference"), null, null, Set.of(), null,
            DateFact.unknown(), DateFact.unknown(), List.of(), now, position,
            DateFact.scheduledDay(e.start(), ID), DateFact.scheduledDay(e.end(), ID), location.isEmpty() ? null : location,
            // ai-deadlines lists conferences; it does not say whether one is online, so the format stays unknown.
            EventKind.CONFERENCE, null));
    }

    /** "2026-12-06" as a string or a YAML date; anything else (e.g. "TBA") is unknown. */
    static LocalDate day(Object v) {
        if (v instanceof Date d) {
            return LocalDate.ofInstant(d.toInstant(), ZoneOffset.UTC);
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length())));
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
        return null;
    }
}
