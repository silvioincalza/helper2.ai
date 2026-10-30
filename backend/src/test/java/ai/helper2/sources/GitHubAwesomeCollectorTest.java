package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubAwesomeCollectorTest {

    static final String SEARCH = """
        {"items":[
          {"full_name":"acme/awesome-llm","stargazers_count":5000},
          {"full_name":"f/prompts.chat","stargazers_count":170000}]}""";

    static final String README = """
        ## Agents
        - [Interpreter](https://github.com/openinterpreter/openinterpreter)
        - [Node](https://github.com/nodejs/node)
        - [Tiny](https://github.com/someone/tiny)
        - [Old](https://github.com/someone/archived)
        - [Gone](https://github.com/someone/deleted)
        """;

    static final String GRAPHQL = """
        {"data":{
          "r0":{"nameWithOwner":"openinterpreter/openinterpreter","url":"https://github.com/openinterpreter/openinterpreter",
                "description":"A natural language interface for computers","stargazerCount":68671,"isArchived":false,
                "isFork":false,"createdAt":"2023-07-14T00:00:00Z","pushedAt":"2026-10-20T00:00:00Z",
                "licenseInfo":{"spdxId":"AGPL-3.0"},"repositoryTopics":{"nodes":[{"topic":{"name":"llm"}}]}},
          "r1":{"nameWithOwner":"nodejs/node","url":"https://github.com/nodejs/node","description":"Node.js JavaScript runtime",
                "stargazerCount":123092,"isArchived":false,"isFork":false,"licenseInfo":null,"repositoryTopics":{"nodes":[]}},
          "r2":{"nameWithOwner":"someone/tiny","url":"https://github.com/someone/tiny","description":"LLM toy",
                "stargazerCount":3,"isArchived":false,"isFork":false,"repositoryTopics":{"nodes":[]}},
          "r3":{"nameWithOwner":"someone/archived","url":"https://github.com/someone/archived","description":"LLM thing",
                "stargazerCount":900,"isArchived":true,"isFork":false,"repositoryTopics":{"nodes":[]}},
          "r4":null},
         "errors":[{"type":"NOT_FOUND","path":["r4"]}]}""";

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    /** Answers like the model: the interpreter is an AI agent, Node is not AI. */
    static final class FakeModel implements RepoClassifier {
        final List<List<Facts>> calls = new java.util.ArrayList<>();
        RuntimeException failure;

        @Override
        public Map<String, Verdict> classify(List<Facts> repos) {
            calls.add(repos);
            if (failure != null) {
                throw failure;
            }
            Map<String, Verdict> out = new HashMap<>();
            for (Facts f : repos) {
                out.put(f.id(), f.fullName().startsWith("nodejs/")
                    ? new Verdict(false, ResourceType.OTHER) : new Verdict(true, ResourceType.AGENT));
            }
            return out;
        }

        @Override
        public String name() {
            return "fake-nano";
        }
    }

    GitHubAwesomeCollector collector(RepoClassifier classifier, int perRun, String token) {
        return new GitHubAwesomeCollector(new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK, Resilience.NONE, new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"), new GitHubAwesomeCollector.Limits(10, 1000, 1000, perRun, 50, 25),
            classifier);
    }

    void routes() {
        server.route("/search/repositories", FixtureServer.Reply.ok(SEARCH));
        server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(README));
        server.route("/graphql", FixtureServer.Reply.ok(GRAPHQL));
    }

    @Test
    void keepsOnlyRepositoriesTheModelCallsAiAndThatPassTheFacts() {
        routes();
        FakeModel model = new FakeModel();

        CollectedBatch batch = collector(model, 200, "t").collect(null, null, 500);

        assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("openinterpreter/openinterpreter");
        SourceHit hit = batch.getHits().getFirst();
        assertThat(hit.getSourceId()).isEqualTo("github-awesome");
        assertThat(hit.getType()).isEqualTo(ResourceType.AGENT);
        assertThat(hit.getDescription()).isEqualTo("A natural language interface for computers");
        assertThat(hit.getLicense()).isEqualTo("AGPL-3.0");
        assertThat(hit.getTags()).containsExactly("llm");
        assertThat(hit.getPopularity()).singleElement().satisfies(p -> {
            assertThat(p.getKind()).isEqualTo(PopularityKind.GITHUB_STARS);
            assertThat(p.getValue()).isEqualTo(68671);
        });
        // Too few stars, archived and unresolved repositories never reach the model.
        assertThat(model.calls).singleElement().satisfies(c ->
            assertThat(c).extracting(RepoClassifier.Facts::fullName)
                .containsExactly("openinterpreter/openinterpreter", "nodejs/node"));
        assertThat(model.calls.getFirst().getFirst().listSection()).isEqualTo("Agents");
        assertThat(batch.getNextCursor()).isNull();
        assertThat(batch.getCheckpoint()).isEqualTo(GitHubSourceTest.CLOCK.instant().toString());
    }

    @Test
    void aReadmeLargerThanTheDefaultResponseCapIsStillRead() {
        routes();
        String padded = README + "\n<!-- " + "x".repeat(3 * 1024 * 1024) + " -->\n";
        server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(padded));

        CollectedBatch batch = collector(new FakeModel(), 200, "t").collect(null, null, 500);

        assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("openinterpreter/openinterpreter");
    }

    @Test
    void readsOnlyListsNamedAwesomeAndAsksGraphQlForAllEntriesInOneCall() {
        routes();
        collector(new FakeModel(), 200, "t").collect(null, null, 500);

        assertThat(server.requests).noneMatch(r -> r.contains("prompts.chat"));
        assertThat(server.requests.getFirst()).contains("sort=stars").contains("1000");
        assertThat(server.requests).filteredOn(r -> r.startsWith("/graphql")).hasSize(1);
        assertThat(server.requestBodies).anyMatch(b -> b.contains("r4: repository(owner: \\\"someone\\\", name: \\\"deleted\\\")"));
    }

    @Test
    void fallsBackToKeywordRulesWhenTheModelFails() {
        routes();
        FakeModel model = new FakeModel();
        model.failure = new IllegalStateException("provider down");

        CollectedBatch batch = collector(model, 200, "t").collect(null, null, 500);

        // The heuristic keeps the LLM agent by its topics and drops the Node.js runtime.
        assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("openinterpreter/openinterpreter");
    }

    @Test
    void stopsAtThePerRunBudgetAndResumesFromTheCursor() {
        routes();
        GitHubAwesomeCollector c = collector(new FakeModel(), 2, "t");

        CollectedBatch first = c.collect(null, null, 500);
        assertThat(first.getNextCursor()).isEqualTo("2");
        assertThat(first.getHits()).extracting(SourceHit::getRank).containsExactly(0);

        CollectedBatch second = c.collect(null, "2", 500);
        assertThat(second.getNextCursor()).isEqualTo("4");
        CollectedBatch last = c.collect(null, "4", 500);
        assertThat(last.getNextCursor()).isNull();
    }

    @Test
    void isDisabledWithoutATokenAndNotDueBeforeTheInterval() {
        assertThat(collector(new FakeModel(), 200, "").getDescriptor().getEnabled()).isFalse();

        String lastPass = GitHubSourceTest.CLOCK.instant().minus(Duration.ofHours(2)).toString();
        assertThat(collector(new FakeModel(), 200, "t").collect(lastPass, null, 500).getNotDue()).isTrue();
        assertThat(server.requests).isEmpty();
    }

    @Test
    void aGraphQlRateLimitStopsThePass() {
        server.route("/search/repositories", FixtureServer.Reply.ok(SEARCH));
        server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(README));
        server.route("/graphql", FixtureServer.Reply.ok("{\"errors\":[{\"type\":\"RATE_LIMITED\"}]}"));

        assertThatThrownBy(() -> collector(new FakeModel(), 200, "t").collect(null, null, 500))
            .isInstanceOf(SourceFailure.RateLimited.class);
    }
}
