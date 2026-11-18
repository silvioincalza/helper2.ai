package ai.helper2.scout.internal;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.Material;
import ai.helper2.scout.ModelAnswerInvalid;
import ai.helper2.scout.ValidationResult;
import ai.helper2.shared.ResourceType;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_NEBIUS_API_KEY", matches = ".+")
class EnrichmentNativeSchemaLiveTest {

  static final String BASE_URL = "https://api.tokenfactory.nebius.com/v1";
  static final int ROUNDS = Integer.parseInt(env("H2_ENRICH_PROBE_ROUNDS", "3"));
  final String model = env("H2_NEBIUS_REASONING_MODEL", "nvidia/Nemotron-3.5-Super-VL");
  final int maxTokens = Integer.parseInt(env("H2_NEBIUS_ENRICH_MAX_TOKENS", "8192"));

  record Sample(String title, ResourceType type, String text) {}

  static final List<Sample> SAMPLES =
      List.of(
          new Sample(
              "pg-bridge",
              ResourceType.MCP_SERVER,
              "pg-bridge is an MCP server that lets Claude Desktop query PostgreSQL databases"
                  + " read-only. It is released under the MIT License and runs with npx on Node 20"
                  + " or later."),
          new Sample(
              "tinyvec",
              ResourceType.LIBRARY,
              "tinyvec is a Python library for vector search in memory. Install it with pip"
                  + " install tinyvec. Licensed under the Apache License 2.0."),
          new Sample(
              "Notewise",
              ResourceType.PRODUCT,
              "Notewise is a web app that summarizes meeting recordings. It includes a free plan"
                  + " for teams of up to 5 people; the Pro plan is $12 per month per user. Apps"
                  + " for iOS and Android are available."),
          new Sample(
              "Orca-Coder 7B",
              ResourceType.MODEL,
              "Orca-Coder 7B is an open-weight code model fine-tuned for Python and TypeScript"
                  + " completion. The weights are released under the Apache-2.0 license."),
          new Sample(
              "Building agents with tool calling",
              ResourceType.ARTICLE,
              "A tutorial that shows how to build a research agent with LangGraph and the"
                  + " OpenAI API, step by step, with code for each part."),
          new Sample(
              "shellpilot",
              ResourceType.AGENT,
              "shellpilot is a command-line agent that turns plain English into shell commands"
                  + " and asks before it runs them. It works on macOS and Linux."),
          new Sample(
              "Prompt pack for code review",
              ResourceType.PROMPT,
              "A collection of system prompts that make a chat model review a pull request for"
                  + " bugs, security issues and missing tests."),
          new Sample(
              "OpenDocs-QA",
              ResourceType.DATASET,
              "OpenDocs-QA is a dataset of 40,000 question and answer pairs taken from open"
                  + " source documentation, for training retrieval models. Licensed CC-BY-4.0."),
          new Sample(
              "Intro to LLM evaluation",
              ResourceType.COURSE,
              "A free online course in six lessons on how to evaluate large language model"
                  + " applications, with exercises in Python."),
          new Sample(
              "awesome-local-ai",
              ResourceType.AWESOME_LIST,
              "A curated list of awesome tools for running AI models on your own computer:"
                  + " runtimes, user interfaces and model hubs."));

  record Tally(int calls, int invalid, int accepted, long millis) {
    Tally plus(int invalidNow, int acceptedNow, long millisNow) {
      return new Tally(calls + 1, invalid + invalidNow, accepted + acceptedNow, millis + millisNow);
    }

    String line(String mode) {
      return String.format(
          "%-14s calls %3d  invalid JSON %3d  accepted items %4d  mean %5d ms",
          mode, calls, invalid, accepted, calls == 0 ? 0 : millis / calls);
    }
  }

  @Test
  void theNativeSchemaAgainstTheFormatText() {
    Tally text = run(false);
    Tally schema = run(true);
    System.out.println("Enrichment, " + model + ", " + ROUNDS + " rounds of " + SAMPLES.size());
    System.out.println(text.line("format text"));
    System.out.println(schema.line("native schema"));
  }

  private Tally run(boolean nativeSchema) {
    SpringAiEnrichmentModel enrichment =
        new SpringAiEnrichmentModel(
            chatModel(),
            "nebius",
            model,
            maxTokens,
            nativeSchema,
            new DailyBudget(1000, Clock.systemUTC(), "enrichment-probe"),
            ModelCallMetrics.none(),
            Resilience.NONE);
    EvidenceValidator validator = new EvidenceValidator();
    Tally tally = new Tally(0, 0, 0, 0);
    for (int round = 0; round < ROUNDS; round++) {
      for (Sample sample : SAMPLES) {
        EnrichmentInput input = input(sample);
        long started = System.nanoTime();
        try {
          EnrichmentProposal proposal = enrichment.propose(input);
          ValidationResult result = validator.validate(input, proposal);
          tally = tally.plus(0, result.getAccepted(), elapsed(started));
        } catch (ModelAnswerInvalid e) {
          tally = tally.plus(1, 0, elapsed(started));
        }
      }
    }
    return tally;
  }

  private static EnrichmentInput input(Sample sample) {
    Material material =
        new Material(
            UUID.randomUUID(),
            URI.create("https://example.org/" + sample.title().replace(' ', '-')),
            Instant.now(),
            sample.title() + "\n" + sample.text());
    return new EnrichmentInput(
        sample.title(), List.of(material), Map.of(), Map.of(), sample.type());
  }

  private static long elapsed(long started) {
    return Duration.ofNanos(System.nanoTime() - started).toMillis();
  }

  private OpenAiChatModel chatModel() {
    return OpenAiChatModel.builder()
        .options(
            OpenAiChatOptions.builder()
                .baseUrl(BASE_URL)
                .apiKey(System.getenv("H2_NEBIUS_API_KEY"))
                .model(model)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(0)
                .build())
        .build();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
