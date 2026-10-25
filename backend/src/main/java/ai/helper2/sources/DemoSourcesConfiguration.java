package ai.helper2.sources;

import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * DEMO profile: replaces every live connector with clearly labelled fixture sources so demos and
 * end-to-end tests are deterministic and never call third-party APIs. All data is marked DEMO.
 * Includes one source that always times out and one that always fails, to exercise partial results.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoSourcesConfiguration {

    static final URI DEMO_HOME = URI.create("https://helper2.ai/sources#demo");

    @Bean
    SearchSource demoCatalog(Clock clock) {
        SourceDescriptor d = descriptor("demo-catalog", "Demo catalog (fixture data)", Duration.ofSeconds(2), 15);
        return new SearchSource() {
            @Override
            public SourceDescriptor getDescriptor() {
                return d;
            }

            @Override
            public List<SourceHit> search(SourceQuery q) {
                Instant now = clock.instant();
                List<String> terms = java.util.stream.Stream.concat(
                        java.util.Arrays.stream(q.getText().toLowerCase(Locale.ROOT).split("\\s+")), q.getExpandedTerms().stream())
                    .filter(t -> t.length() > 2).toList();
                List<SourceHit> all = fixtures(now);
                return all.stream()
                    .filter(h -> terms.stream().anyMatch(t -> (h.getTitle() + " " + h.getDescription() + " " + h.getTags()).toLowerCase(Locale.ROOT).contains(t)))
                    .toList();
            }
        };
    }

    @Bean
    SearchSource demoMirror(Clock clock) {
        // A second demo source that reports a different license for one resource: exercises conflict display.
        SourceDescriptor d = descriptor("demo-mirror", "Demo mirror (fixture data)", Duration.ofSeconds(2), 16);
        return new SearchSource() {
            @Override
            public SourceDescriptor getDescriptor() {
                return d;
            }

            @Override
            public List<SourceHit> search(SourceQuery q) {
                Instant now = clock.instant();
                if (!q.getText().toLowerCase(Locale.ROOT).contains("postgres") && !q.getText().toLowerCase(Locale.ROOT).contains("mcp")) {
                    return List.of();
                }
                return List.of(hit("demo-mirror", "demo-pg-mcp", "https://github.com/helper2-demo/pg-mcp", "Demo Postgres MCP server",
                    "Fixture: MCP server exposing a Postgres database to AI assistants.", ResourceType.MCP_SERVER,
                    Audience.BUILD_WITH_AI, "Apache-2.0", "2025-02-01T00:00:00Z", "2026-09-30T00:00:00Z", now, 0, Set.of("mcp", "postgres")));
            }
        };
    }

    @Bean
    SearchSource demoSlow() {
        SourceDescriptor d = descriptor("demo-slow", "Demo slow source (always times out)", Duration.ofMillis(300), 90);
        return new SearchSource() {
            @Override
            public SourceDescriptor getDescriptor() {
                return d;
            }

            @Override
            public List<SourceHit> search(SourceQuery q) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
    }

    @Bean
    SearchSource demoDown() {
        SourceDescriptor d = descriptor("demo-down", "Demo unavailable source (always fails)", Duration.ofSeconds(1), 95);
        return new SearchSource() {
            @Override
            public SourceDescriptor getDescriptor() {
                return d;
            }

            @Override
            public List<SourceHit> search(SourceQuery q) {
                throw new SourceFailure.Unavailable("demo outage");
            }
        };
    }

    private static SourceDescriptor descriptor(String id, String name, Duration timeout, int priority) {
        return new SourceDescriptor(id, name, SourceMode.DEMO, DEMO_HOME, null, "Fixture data for demos and tests. Not real.",
            EnumSet.allOf(ResourceType.class), timeout, Duration.ofMinutes(5), true, priority);
    }

    static List<SourceHit> fixtures(Instant now) {
        return List.of(
            hit("demo-catalog", "demo-pg-mcp", "https://github.com/helper2-demo/pg-mcp", "Demo Postgres MCP server",
                "Fixture: MCP server exposing a Postgres database to AI assistants.", ResourceType.MCP_SERVER,
                Audience.BUILD_WITH_AI, "MIT", "2025-01-15T00:00:00Z", "2026-10-01T00:00:00Z", now, 0, Set.of("mcp", "postgres", "database")),
            hit("demo-catalog", "demo-writer", "https://demo.helper2.ai/writer", "Demo Writing Assistant",
                "Fixture: drafts and rewrites emails, blog posts and newsletters. Supports translation.", ResourceType.PRODUCT,
                Audience.USE_AI, null, null, null, now, 1, Set.of("writing", "translation", "newsletter")),
            hit("demo-catalog", "demo-model", "https://huggingface.co/helper2-demo/tiny-llm", "helper2-demo/tiny-llm",
                "Fixture: small open model for local text generation and summarization.", ResourceType.MODEL,
                Audience.BUILD_WITH_AI, "Apache-2.0", "2024-06-01T00:00:00Z", "2025-03-01T00:00:00Z", now, 2, Set.of("text-generation", "summarization", "llm")),
            hit("demo-catalog", "demo-agent", "https://github.com/helper2-demo/research-agent", "Demo research agent",
                "Fixture: AI agent that searches papers and writes summaries with citations.", ResourceType.AGENT,
                Audience.BOTH, null, "2026-02-10T00:00:00Z", null, now, 3, Set.of("agent", "research", "summarization")),
            hit("demo-catalog", "demo-prompts", "https://github.com/helper2-demo/prompt-pack", "Demo prompt pack for writing",
                "Fixture: reusable prompts for writing, editing and translation tasks.", ResourceType.PROMPT,
                Audience.BOTH, "CC0-1.0", null, "2026-08-20T00:00:00Z", now, 4, Set.of("prompt", "writing", "translation")));
    }

    private static SourceHit hit(String source, String ext, String url, String title, String description, ResourceType type,
                                 Audience audience, String license, String published, String updated, Instant now, int rank,
                                 Set<String> tags) {
        return new SourceHit(source, ext, URI.create(url), CanonicalUrl.of(url), null, title, description, ValueOrigin.DEMO,
            type, audience, null, new ai.helper2.search.QueryAnalyzer().useCasesOf(title, description, String.join(" ", tags)),
            tags, license, null, Set.of(), Classification.openSource(license),
            DateFact.fromSource(published == null ? null : Instant.parse(published), source, now),
            DateFact.fromSource(updated == null ? null : Instant.parse(updated), source, now),
            List.of(),
            now, rank);
    }
}
