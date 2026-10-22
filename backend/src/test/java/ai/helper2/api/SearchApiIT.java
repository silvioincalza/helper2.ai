package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.SourceFailure;
import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

class SearchApiIT extends ApiTestBase {

    @Autowired
    JdbcClient jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;


    String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aggregatesLiveAndCuratedAndDisclosesEverySource() throws Exception {
        FAKE.set(q -> List.of(fakeHit("https://github.com/acme/translator-ai", "acme/translator-ai",
            "AI translation library", ResourceType.LIBRARY, "MIT", "2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 0)));
        MvcResult r = mvc.perform(get("/api/v1/search").param("q", "translation").param("path", "USE_AI"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverage.note").value(org.hamcrest.Matchers.containsString("not search the whole web")))
            .andExpect(jsonPath("$.sources[?(@.sourceId=='github')].outcome").value("SKIPPED"))
            .andExpect(jsonPath("$.sources[?(@.sourceId=='fake-live')].outcome").value("SUCCEEDED"))
            .andExpect(jsonPath("$.sources[?(@.sourceId=='curated')].mode").value("CURATED"))
            .andReturn();
        String body = r.getResponse().getContentAsString();
        ContractValidator.assertMatches("SearchResponse", body);
        assertThat(body).contains("acme/translator-ai").contains("DeepL Translator");
    }

    @Test
    void failingSourceGivesPartialResultsNotAnError() throws Exception {
        FAKE.set(q -> {
            throw new SourceFailure.Unavailable("boom");
        });
        String body = mvc.perform(get("/api/v1/search").param("q", "writing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverage.partial").value(true))
            .andExpect(jsonPath("$.sources[?(@.sourceId=='fake-live')].outcome").value("FAILED"))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("SearchResponse", body);
        assertThat(body).contains("Grammarly");
    }

    @Test
    void slowSourceTimesOutWithinBudget() throws Exception {
        FAKE.set(q -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        long start = System.currentTimeMillis();
        mvc.perform(get("/api/v1/search").param("q", unique("slow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sources[?(@.sourceId=='fake-live')].outcome").value("TIMED_OUT"));
        assertThat(System.currentTimeMillis() - start).isLessThan(2_500);
    }

    @Test
    void repeatedSearchIsServedFromRedis() throws Exception {
        String q = unique("cache me");
        FAKE.set(x -> List.of(fakeHit("https://github.com/acme/cached-ai", "acme/cached-ai", "AI", ResourceType.LIBRARY,
            null, null, null, 0)));
        mvc.perform(get("/api/v1/search").param("q", q)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/search").param("q", q))
            .andExpect(jsonPath("$.sources[?(@.sourceId=='fake-live')].servedFrom").value("CACHE"))
            .andExpect(jsonPath("$.results[0].freshness").value("CACHED"));
        assertThat(FAKE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void unknownDatesAreExplicitAndFiltersAndSortWork() throws Exception {
        String q = unique("datesort");
        FAKE.set(x -> List.of(
            fakeHit("https://github.com/acme/old-ai", "old", "AI", ResourceType.LIBRARY, "MIT", "2022-01-01T00:00:00Z", "2023-01-01T00:00:00Z", 0),
            fakeHit("https://github.com/acme/new-ai", "new", "AI", ResourceType.MCP_SERVER, "MIT", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", 1),
            fakeHit("https://github.com/acme/nodate-ai", "nodate", "AI", ResourceType.LIBRARY, null, null, null, 2)));
        mvc.perform(get("/api/v1/search").param("q", q).param("sort", "NEWEST").param("source", "fake-live"))
            .andExpect(jsonPath("$.results[0].title").value("new"))
            .andExpect(jsonPath("$.results[2].title").value("nodate"))
            .andExpect(jsonPath("$.results[2].timeline.published.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.results[2].timeline.published.value").doesNotExist());
        mvc.perform(get("/api/v1/search").param("q", q).param("license", "unknown").param("source", "fake-live"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.results[0].title").value("nodate"));
        mvc.perform(get("/api/v1/search").param("q", q).param("type", "MCP_SERVER"))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void discoveredDateIsRecordedByTheCatalogAndDetailPageWorks() throws Exception {
        String url = "https://github.com/acme/detail-ai-" + UUID.randomUUID().toString().substring(0, 8);
        FAKE.set(x -> List.of(fakeHit(url, "acme/detail-ai", "AI helper", ResourceType.LIBRARY,
            "MIT", "2025-01-01T00:00:00Z", null, 0)));
        // First sighting: recorded in the background, so this response cannot know the date yet.
        String body = mvc.perform(get("/api/v1/search").param("q", unique("detail")))
            .andExpect(jsonPath("$.results[0].timeline.discovered.status").value("UNKNOWN"))
            .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.results[0].id");
        eventually(() -> mvc.perform(get("/api/v1/resources/" + id)).andExpect(status().isOk()));
        String detail = mvc.perform(get("/api/v1/resources/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.card.title").value("acme/detail-ai"))
            .andExpect(jsonPath("$.card.timeline.discovered.status").value("KNOWN"))
            .andExpect(jsonPath("$.sourceRecords[0].sourceId").value("fake-live"))
            .andExpect(jsonPath("$.assertions[?(@.field=='license')].origin").value("SOURCE_PROVIDED"))
            .andExpect(jsonPath("$.enrichmentStatus").value("NOT_RUN"))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("ResourceDetail", detail);
        mvc.perform(get("/api/v1/search").param("q", unique("detail again")))
            .andExpect(jsonPath("$.results[0].timeline.discovered.status").value("KNOWN"));
    }

    @Test
    void licenceFilterIgnoresCase() throws Exception {
        String q = unique("licence case");
        FAKE.set(x -> List.of(fakeHit("https://github.com/acme/mit-ai", "mit-ai", "AI", ResourceType.LIBRARY, "MIT", null, null, 0)));
        mvc.perform(get("/api/v1/search").param("q", q).param("license", "mit").param("source", "fake-live"))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void tooManyFilterValuesAreRejected() throws Exception {
        var request = get("/api/v1/search").param("q", "ok");
        for (int i = 0; i < 21; i++) {
            request = request.param("platform", "p" + i);
        }
        mvc.perform(request).andExpect(status().isBadRequest());
    }

    @Test
    void italianQueryIsExpandedAndDisclosed() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "app per tradurre"))
            .andExpect(jsonPath("$.interpretation.expandedTerms[0]").value("translation"))
            .andExpect(jsonPath("$.notices[?(@.code=='QUERY_EXPANDED')]").exists());
    }

    @Test
    void openSourceUnknownBindsByWireValue() throws Exception {
        // Was 400 before ContractEnumConverters: the enum bound by constant name, not by its wire value.
        mvc.perform(get("/api/v1/search").param("q", "x").param("openSource", "unknown")).andExpect(status().isOk());
    }

    @Test
    void cacheKeysNeverContainRawQueryText() throws Exception {
        FAKE.set(q -> java.util.List.of(fakeHit("https://github.com/acme/secret-agent", "secret agent", "An agent",
            ai.helper2.shared.ResourceType.AGENT, null, null, null, 0)));
        mvc.perform(get("/api/v1/search").param("q", "my secret query")).andExpect(status().isOk());
        // Something was cached (else this test proves nothing), and no key carries the user's words.
        assertThat(redis.keys("*")).isNotEmpty().noneMatch(k -> k.contains("secret"));
    }

    @Test
    void invalidInputIsRejectedAsProblemJson() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "x".repeat(201))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("q", "ok").param("size", "500")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("q", "ok").param("type", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownResourceIs404() throws Exception {
        mvc.perform(get("/api/v1/resources/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void sourcesEndpointListsEveryConfiguredSource() throws Exception {
        String body = mvc.perform(get("/api/v1/sources")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertArrayMatches("SourceInfo", body);
        assertThat(body).contains("\"github\"").contains("\"mcp-registry\"").contains("\"curated\"").contains("\"helper2-index\"");
    }

    @Test
    void sourcesEndpointShowsTheScoutOutcomeOfCollectorsThatAreNeverSearched() throws Exception {
        jdbc.sql("UPDATE source SET last_outcome = 'SUCCEEDED', last_checked_at = '2026-10-26T12:00:00Z' WHERE id = 'mcp-registry'").update();

        mvc.perform(get("/api/v1/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == 'mcp-registry')].lastOutcome").value("SUCCEEDED"))
            .andExpect(jsonPath("$[?(@.id == 'mcp-registry')].lastCheckedAt").exists());
    }
}
