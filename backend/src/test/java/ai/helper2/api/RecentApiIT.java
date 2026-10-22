package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The home page "Today" list: newest by the date the SOURCE states, from Helper2's own index. */
class RecentApiIT extends ApiTestBase {

    private static String daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days)).toString();
    }

    @Test
    void newestFirstByStatedDateOnlyRecentAndNeverFuture() throws Exception {
        // A search stores what it saw in the index (as observed resources): that is the data the list reads.
        FAKE.set(q -> List.of(
            fakeHit("https://github.com/acme/recent-mcp", "recent-mcp", "MCP server published two days ago", ResourceType.MCP_SERVER, "MIT", daysAgo(2), null, 0),
            fakeHit("https://github.com/acme/newest-model", "newest-model", "Model published yesterday", ResourceType.MODEL, "MIT", daysAgo(1), null, 1),
            fakeHit("https://github.com/acme/old-lib", "old-lib", "Library published long ago", ResourceType.LIBRARY, "MIT", daysAgo(200), null, 2),
            fakeHit("https://github.com/acme/no-date", "no-date", "Library without a stated date", ResourceType.LIBRARY, "MIT", null, null, 3),
            fakeHit("https://github.com/acme/future", "future", "Clock skew: dated next week", ResourceType.LIBRARY, "MIT", Instant.now().plus(Duration.ofDays(7)).toString(), null, 4)));
        mvc.perform(get("/api/v1/search").param("q", "acme")).andExpect(status().isOk());

        eventually(() -> {
            String body = mvc.perform(get("/api/v1/recent").param("days", "14"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            ContractValidator.assertMatches("RecentResponse", body);

            List<String> titles = titles(body);
            assertThat(titles).containsSubsequence("newest-model", "recent-mcp");
            assertThat(titles).doesNotContain("old-lib", "no-date", "future");
        });
    }

    @Test
    void filtersByType() throws Exception {
        FAKE.set(q -> List.of(
            fakeHit("https://github.com/acme/typed-mcp", "typed-mcp", "MCP server published recently", ResourceType.MCP_SERVER, "MIT", daysAgo(3), null, 0),
            fakeHit("https://github.com/acme/typed-model", "typed-model", "Model published recently", ResourceType.MODEL, "MIT", daysAgo(3), null, 1)));
        mvc.perform(get("/api/v1/search").param("q", "typed")).andExpect(status().isOk());

        eventually(() -> {
            String body = mvc.perform(get("/api/v1/recent").param("type", "MCP_SERVER"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(titles(body)).contains("typed-mcp").doesNotContain("typed-model");
        });
    }

    @Test
    void rejectsOutOfRangeParameters() throws Exception {
        mvc.perform(get("/api/v1/recent").param("days", "31")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/recent").param("size", "0")).andExpect(status().isBadRequest());
    }

    private static List<String> titles(String body) {
        return JsonPath.read(body, "$.items[*].title");
    }
}
