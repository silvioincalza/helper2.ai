package ai.searchavo.sources.research;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.research.ResearchTool;
import ai.searchavo.sources.support.HttpClients;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class HuggingFaceTool implements ResearchTool {

  static final String POLICY = "research-huggingface";
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{1,96}/[A-Za-z0-9._-]{1,96}$");

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;

  public HuggingFaceTool(RestClient http, Resilience resilience, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.clock = clock;
    resilience.require(POLICY);
  }

  @Override
  public String name() {
    return "huggingface";
  }

  @Override
  public String description() {
    return "Read a Hugging Face model's record: task, tags, licence, downloads, last change.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"model":{"type":"string","description":"owner/name"}},\
    "required":["model"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String id = Args.of(arguments).text("model");
    if (id == null || !ID.matcher(id).matches()) {
      return Output.error("model must be owner/name");
    }
    JsonNode m;
    try {
      m =
          resilience.call(
              POLICY,
              () ->
                  HttpClients.getJson(
                      () -> http.get().uri("/api/models/{id}", id).retrieve().body(String.class),
                      clock));
    } catch (HttpClientErrorException.NotFound e) {
      return new Output("not found: " + id, null);
    }
    List<String> tags = new ArrayList<>();
    m.path("tags").forEach(t -> tags.add(t.asString()));
    String license = m.path("cardData").path("license").asString("");
    return new Output(
        "model: "
            + m.path("id").asString(id)
            + "\ntask: "
            + m.path("pipeline_tag").asString("")
            + "\nlicense: "
            + (license.isBlank() ? "none stated" : license)
            + "\ntags: "
            + String.join(", ", tags)
            + "\ndownloads: "
            + m.path("downloads").asLong(0)
            + "\nlast change: "
            + m.path("lastModified").asString(""),
        "https://huggingface.co/" + m.path("id").asString(id),
        List.of("https://huggingface.co/" + m.path("id").asString(id)));
  }
}
