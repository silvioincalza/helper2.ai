package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Daily web searches (ADR 0037) as a visitor sees them: a metered web source stands in for Tavily. Each test asks from
 * its own address, so its counters start at zero.
 */
@TestPropertySource(properties = {
    "helper2.tavily.quota.key-secret=test-secret",
    "resilience4j.bulkhead.instances.source-fake-web.base-config=source",
    // Its own context stays cached with the others until the JVM ends: a small pool keeps PostgreSQL under its
    // connection limit. Closing the context instead would stop the shared containers.
    "spring.datasource.hikari.maximum-pool-size=2",
})
@Import(WebSearchQuotaApiIT.FakeWeb.class)
class WebSearchQuotaApiIT extends ApiTestBase {

    static final AtomicInteger WEB_CALLS = new AtomicInteger();

    /** One address per test, so no two tests share a counter (a random pick of 250 sometimes did). */
    private static final AtomicInteger ADDRESSES = new AtomicInteger(10);

    private final String address = "198.51.100." + ADDRESSES.incrementAndGet();
    private final RequestPostProcessor visitor = request -> {
        request.setRemoteAddr(address);
        return request;
    };

    private String search(String q) throws Exception {
        return mvc.perform(get("/api/v1/search").param("q", q).with(visitor))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Object read(String json, String path) {
        return com.jayway.jsonpath.JsonPath.read(json, path);
    }

    @Test
    void eachSearchShowsTheWebSearchesLeftAndAtTheLimitTheWebSourceIsSkipped() throws Exception {
        String run = UUID.randomUUID().toString();
        int before = WEB_CALLS.get();

        for (int left = 2; left >= 0; left--) {
            String json = search("agents " + run + " " + left);
            assertThat(read(json, "$.webSearches.limit")).isEqualTo(3);
            assertThat(read(json, "$.webSearches.remaining")).isEqualTo(left);
            ContractValidator.assertMatches("SearchResponse", json);
        }
        String over = search("agents " + run + " over");

        assertThat(WEB_CALLS.get() - before).isEqualTo(3);
        assertThat(read(over, "$.webSearches.remaining")).isEqualTo(0);
        assertThat(read(over, "$.sources[?(@.sourceId == 'fake-web')].outcome")).asList().containsExactly("SKIPPED");
        assertThat(read(over, "$.sources[?(@.sourceId == 'fake-web')].message")).asList()
            .containsExactly("Daily limit of web searches reached; back at 00:00 UTC");
        assertThat(read(over, "$.sources[?(@.sourceId == 'fake-live')].outcome")).asList().containsExactly("SUCCEEDED");
    }

    @Test
    void aQuestionAlreadyStoredIsAnsweredWithoutAWebSearch() throws Exception {
        String q = "rag frameworks " + UUID.randomUUID();
        search(q);
        // The hits reach the catalog in the background; until then the stored set is a miss.
        eventually(() -> assertThat(read(search(q), "$.sources[?(@.sourceId == 'fake-web')].servedFrom")).asList().containsExactly("CACHE"));
        int calls = WEB_CALLS.get();
        Object left = read(search(q), "$.webSearches.remaining");

        String json = search(q);

        assertThat(WEB_CALLS.get()).isEqualTo(calls);
        assertThat(read(json, "$.webSearches.remaining")).isEqualTo(left);
        assertThat(read(json, "$.sources[?(@.sourceId == 'fake-web')].resultCount")).asList().containsExactly(1);
    }

    @Test
    void theGuideCountsItsOwnWebSearches() throws Exception {
        search("agents " + UUID.randomUUID());

        String json = mvc.perform(post("/api/v1/guide").with(csrf()).with(visitor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"request\":\"open source agent frameworks " + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.webSearches.limit").value(3))
            .andExpect(jsonPath("$.webSearches.remaining").value(2))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("GuideResponse", json);
    }

    private Number creditsToday() throws Exception {
        String body = mvc.perform(get("/api/v1/sources")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertArrayMatches("SourceInfo", body);
        assertThat(read(body, "$[?(@.id == 'fake-web')].webCredits.dailyCap")).asList().containsExactly((Object) null);
        assertThat(read(body, "$[?(@.id == 'fake-live')].webCredits")).asList().containsOnlyNulls();
        return (Number) ((List<?>) read(body, "$[?(@.id == 'fake-web')].webCredits.usedToday")).getFirst();
    }

    @Test
    void theSourcesPageShowsTheCreditsOfTheDayForAllVisitorsWithoutACap() throws Exception {
        long before = creditsToday().longValue();

        search("agents " + UUID.randomUUID());

        assertThat(creditsToday().longValue()).isEqualTo(before + 1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeWeb {
        @Bean
        SearchSource fakeWebSource() {
            SourceDescriptor d = new SourceDescriptor("fake-web", "Fake web search", SourceMode.LIVE,
                URI.create("https://web.example"), null, null, EnumSet.of(ResourceType.NEWS),
                Duration.ofMillis(800), Duration.ZERO, true, 48, true, true, true, true, true);
            return new SearchSource() {
                @Override
                public SourceDescriptor getDescriptor() {
                    return d;
                }

                @Override
                public List<SourceHit> search(SourceQuery query) {
                    WEB_CALLS.incrementAndGet();
                    String url = "https://news.example/" + UUID.nameUUIDFromBytes(query.getText().getBytes());
                    return List.of(new SourceHit("fake-web", CanonicalUrl.of(url).getValue(), URI.create(url), CanonicalUrl.of(url),
                        null, "AI news about " + query.getText(), "An AI agent framework launched", ValueOrigin.SOURCE_PROVIDED,
                        ResourceType.NEWS, Audience.BOTH, null, Set.of(), Set.of(), null, null, Set.of(), null,
                        DateFact.unknown(), DateFact.unknown(), List.of(), Instant.now(), 0));
                }
            };
        }
    }
}
