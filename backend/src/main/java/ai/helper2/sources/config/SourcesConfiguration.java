package ai.helper2.sources.config;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.OutboundThrottle;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.aideadlines.AiDeadlinesCollector;
import ai.helper2.sources.arxiv.ArxivSource;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.classifier.SpringAiRepoClassifier;
import ai.helper2.sources.github.GitHubAwesomeCollector;
import ai.helper2.sources.github.GitHubSource;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.huggingface.HuggingFaceProviderOrgsCollector;
import ai.helper2.sources.huggingface.HuggingFaceSpacesSource;
import ai.helper2.sources.huggingface.HuggingFaceTrendingCollector;
import ai.helper2.sources.mcp.McpRegistryCollector;
import ai.helper2.sources.npm.NpmSource;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.CourseCollector;
import ai.helper2.sources.tavily.TavilyCollector;
import ai.helper2.sources.tavily.TavilySource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourcesConfiguration.SourcesProperties.class, CollectorProperties.HfTrending.class,
    CollectorProperties.TavilyScout.class, CollectorProperties.Courses.class, CollectorProperties.GitHubAwesome.class,
    CollectorProperties.HfProviderOrgs.class, CollectorProperties.AiDeadlines.class, CollectorProperties.McpRegistry.class})
@Profile("!demo")
class SourcesConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SourcesConfiguration.class);

    @ConfigurationProperties("helper2")
    record SourcesProperties(Map<String, SourceSettings> sources) {
        SourceSettings get(String key) {
            SourceSettings s = sources == null ? null : sources.get(key);
            if (s == null) {
                throw new IllegalStateException("missing helper2.sources." + key);
            }
            return new SourceSettings(s.enabled(), s.baseUrl(), s.token(),
                s.timeout() == null ? Duration.ofSeconds(2) : s.timeout(),
                s.cacheTtl() == null ? Duration.ofMinutes(30) : s.cacheTtl());
        }
    }

    @Bean
    GitHubSource gitHubSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
        SourceSettings s = p.get("github");
        return new GitHubSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
    }

    @Bean
    HuggingFaceModelsSource huggingFaceModelsSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
        SourceSettings s = p.get("huggingface");
        return new HuggingFaceModelsSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
    }

    @Bean
    HuggingFaceSpacesSource huggingFaceSpacesSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
        SourceSettings s = p.get("huggingface");
        return new HuggingFaceSpacesSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
    }

    @Bean
    NpmSource npmSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
        SourceSettings s = p.get("npm");
        return new NpmSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
    }

    @Bean
    ArxivSource arxivSource(SourcesProperties p, Clock clock, ObservationRegistry observations, OutboundThrottle throttle) {
        SourceSettings s = p.get("arxiv");
        return new ArxivSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, throttle);
    }

    @Bean
    TavilySource tavilySource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
        SourceSettings s = p.get("tavily");
        return new TavilySource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
    }

    @Bean
    HuggingFaceTrendingCollector huggingFaceTrendingCollector(SourcesProperties p, CollectorProperties.HfTrending c, Clock clock,
            Resilience resilience, ObservationRegistry observations) {
        SourceSettings s = p.get("hf-trending");
        return new HuggingFaceTrendingCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience,
            new RunInterval(c.interval()), c.minLikes());
    }

    @Bean
    TavilyCollector tavilyCollector(SourcesProperties p, CollectorProperties.TavilyScout c, Clock clock, Resilience resilience,
            ObservationRegistry observations) {
        SourceSettings s = p.get("tavily-scout");
        return new TavilyCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(c.interval()),
            c.queries());
    }

    /** Weekly Tavily searches on learning platforms; what it finds goes to the admin review list (ADR 0032 §8). */
    @Bean
    CourseCollector courseCollector(SourcesProperties p, CollectorProperties.Courses c, Clock clock, Resilience resilience,
            ObservationRegistry observations) {
        SourceSettings s = p.get("course-scout");
        return new CourseCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(c.interval()),
            c.query(), c.platforms());
    }

    /**
     * The fast model classifies awesome-list entries when an LLM is configured; otherwise keyword rules do.
     * Its daily call cap is counted in Redis, so a restart does not reset the day.
     */
    @Bean
    GitHubAwesomeCollector gitHubAwesomeCollector(SourcesProperties p, CollectorProperties.GitHubAwesome c, Clock clock, Resilience resilience,
            ObservationRegistry observations, ObjectProvider<ChatModel> chatModel, LlmSettings llm, CallCounter budgetCounter,
            ModelCallMetrics metrics, MeterRegistry meters) {
        SourceSettings s = p.get("github-awesome");
        ChatModel model = chatModel.getIfAvailable();
        RepoClassifier classifier;
        if (!llm.usable(model) || llm.fast().isEmpty()) {
            classifier = new RepoClassifier.Heuristic();
        } else {
            DailyBudget budget = new DailyBudget(c.classifierDailyCalls(), clock, "classifier", budgetCounter);
            Gauge.builder("helper2.scout.classifier.budget.remaining", budget, DailyBudget::remaining)
                .description("Awesome-list classifier model calls left under today's cap (UTC day)")
                .register(meters);
            classifier = new SpringAiRepoClassifier(model, llm.fast(), c.classifyMaxTokens(), budget, metrics);
        }
        log.info("GitHub awesome lists: enabled={}, classifier={}", s.enabled() && s.hasToken(), classifier.name());
        return new GitHubAwesomeCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(c.interval()),
            c.topicQueries(),
            new GitHubAwesomeCollector.Limits(c.maxLists(), c.minListStars(), c.maxEntriesPerPass(), c.maxEntriesPerRun(), c.minRepoStars(),
                c.classifyBatch()),
            classifier);
    }

    @Bean
    HuggingFaceProviderOrgsCollector huggingFaceProviderOrgsCollector(SourcesProperties p, CollectorProperties.HfProviderOrgs c, Clock clock,
            Resilience resilience, ObservationRegistry observations) {
        SourceSettings s = p.get("hf-provider-orgs");
        return new HuggingFaceProviderOrgsCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience,
            new RunInterval(c.interval()), c.orgs(), c.perOrg());
    }

    @Bean
    AiDeadlinesCollector aiDeadlinesCollector(SourcesProperties p, CollectorProperties.AiDeadlines c, Clock clock, Resilience resilience,
            ObservationRegistry observations) {
        SourceSettings s = p.get("ai-deadlines");
        return new AiDeadlinesCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(c.interval()));
    }

    @Bean
    McpRegistryCollector mcpRegistryCollector(SourcesProperties p, CollectorProperties.McpRegistry c, Clock clock, Resilience resilience,
            ObservationRegistry observations) {
        SourceSettings s = p.get("mcp-registry");
        return new McpRegistryCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(c.interval()));
    }
}
