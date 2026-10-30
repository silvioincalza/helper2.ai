package ai.helper2.sources;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.OutboundThrottle;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourcesConfiguration.SourcesProperties.class)
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
    HuggingFaceTrendingCollector huggingFaceTrendingCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.hf-trending.interval:P1D}") Duration interval,
            @Value("${helper2.scout.hf-trending.min-likes:20}") int minLikes) {
        SourceSettings s = p.get("hf-trending");
        return new HuggingFaceTrendingCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience,
            new RunInterval(interval), minLikes);
    }

    @Bean
    TavilyCollector tavilyCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.tavily.interval:P5D}") Duration interval,
            @Value("${helper2.scout.tavily.queries:}") List<String> queries) {
        SourceSettings s = p.get("tavily-scout");
        return new TavilyCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(interval),
            queries.stream().map(String::trim).filter(q -> !q.isEmpty()).toList());
    }

    /** Weekly Tavily searches on learning platforms; what it finds goes to the admin review list (ADR 0032 §8). */
    @Bean
    CourseCollector courseCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.courses.interval:P7D}") Duration interval,
            @Value("${helper2.scout.courses.query:new artificial intelligence course or certification}") String query,
            @Value("${helper2.scout.courses.platforms:}") List<String> platforms) {
        SourceSettings s = p.get("course-scout");
        return new CourseCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(interval), query,
            platforms.stream().map(String::trim).filter(x -> !x.isEmpty()).toList());
    }

    /**
     * The fast model classifies awesome-list entries when an LLM is configured; otherwise keyword rules do.
     * Its daily call cap is counted in Redis, so a restart does not reset the day.
     */
    @Bean
    GitHubAwesomeCollector gitHubAwesomeCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations, ObjectProvider<ChatModel> chatModel,
            LlmSettings llm, CallCounter budgetCounter, ModelCallMetrics metrics, MeterRegistry meters,
            @Value("${helper2.scout.github-awesome.interval:P1D}") Duration interval,
            @Value("${helper2.scout.github-awesome.topic-queries:topic:awesome-list topic:llm}") List<String> topicQueries,
            @Value("${helper2.scout.github-awesome.max-lists:10}") int maxLists,
            @Value("${helper2.scout.github-awesome.min-list-stars:1000}") int minListStars,
            @Value("${helper2.scout.github-awesome.max-entries-per-pass:1000}") int maxEntriesPerPass,
            @Value("${helper2.scout.github-awesome.max-entries-per-run:200}") int maxEntriesPerRun,
            @Value("${helper2.scout.github-awesome.min-repo-stars:50}") int minRepoStars,
            @Value("${helper2.scout.github-awesome.classify-batch:25}") int classifyBatch,
            @Value("${helper2.scout.github-awesome.classify-max-tokens:8192}") int classifyMaxTokens,
            @Value("${helper2.scout.github-awesome.classifier-daily-calls:300}") int classifierDailyCalls) {
        SourceSettings s = p.get("github-awesome");
        ChatModel model = chatModel.getIfAvailable();
        RepoClassifier classifier;
        if (!llm.usable(model) || llm.fast().isEmpty()) {
            classifier = new RepoClassifier.Heuristic();
        } else {
            DailyBudget budget = new DailyBudget(classifierDailyCalls, clock, "classifier", budgetCounter);
            Gauge.builder("helper2.scout.classifier.budget.remaining", budget, DailyBudget::remaining)
                .description("Awesome-list classifier model calls left under today's cap (UTC day)")
                .register(meters);
            classifier = new SpringAiRepoClassifier(model, llm.fast(), classifyMaxTokens, budget, metrics);
        }
        log.info("GitHub awesome lists: enabled={}, classifier={}", s.enabled() && s.hasToken(), classifier.name());
        return new GitHubAwesomeCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(interval),
            topicQueries.stream().map(String::trim).filter(q -> !q.isEmpty()).toList(),
            new GitHubAwesomeCollector.Limits(maxLists, minListStars, maxEntriesPerPass, maxEntriesPerRun, minRepoStars,
                classifyBatch),
            classifier);
    }

    @Bean
    HuggingFaceProviderOrgsCollector huggingFaceProviderOrgsCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.hf-provider-orgs.interval:P1D}") Duration interval,
            @Value("${helper2.scout.hf-provider-orgs.orgs:meta-llama,nvidia,mistralai,deepseek-ai,Qwen,google,openai,xai-org}") List<String> orgs,
            @Value("${helper2.scout.hf-provider-orgs.per-org:20}") int perOrg) {
        SourceSettings s = p.get("hf-provider-orgs");
        return new HuggingFaceProviderOrgsCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience,
            new RunInterval(interval), orgs.stream().map(String::trim).filter(o -> !o.isEmpty()).toList(), perOrg);
    }

    @Bean
    AiDeadlinesCollector aiDeadlinesCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.ai-deadlines.interval:P1D}") Duration interval) {
        SourceSettings s = p.get("ai-deadlines");
        return new AiDeadlinesCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(interval));
    }

    @Bean
    McpRegistryCollector mcpRegistryCollector(SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations,
            @Value("${helper2.scout.mcp-registry.interval:PT1H}") Duration interval) {
        SourceSettings s = p.get("mcp-registry");
        return new McpRegistryCollector(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, resilience, new RunInterval(interval));
    }
}
