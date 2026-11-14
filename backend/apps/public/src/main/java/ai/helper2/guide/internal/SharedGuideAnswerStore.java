package ai.helper2.guide.internal;

import ai.helper2.guide.GuideAnswer;
import ai.helper2.guide.GuideAnswerStore;
import ai.helper2.platform.cache.SharedCache;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.module.kotlin.KotlinModule;

class SharedGuideAnswerStore implements GuideAnswerStore {

  static final int VERSION = 2;
  private static final Logger log = LoggerFactory.getLogger(SharedGuideAnswerStore.class);

  private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._-]{1,40}");

  private final SharedCache cache;
  private final ObjectMapper json;
  private final String build;

  SharedGuideAnswerStore(SharedCache cache, ObjectMapper json, String build) {
    this.cache = cache;
    this.json =
        json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    this.build = build != null && BUILD.matcher(build).matches() ? build : "unknown";
  }

  @Override
  public GuideAnswer get(String key) {
    String raw = cache.get(cache.key(build, key));
    return raw == null ? null : decode(raw);
  }

  @Override
  public void put(String key, GuideAnswer answer, Duration ttl) {
    String encoded;
    try {
      encoded = encode(answer);
    } catch (RuntimeException e) {
      log.warn("guide answer not stored, could not encode it: {}", e.getClass().getSimpleName());
      return;
    }
    cache.put(cache.key(build, key), encoded, ttl);
  }

  String encode(GuideAnswer answer) {
    ObjectNode root = json.createObjectNode();
    root.put("v", VERSION);
    root.set("answer", json.valueToTree(answer));
    return json.writeValueAsString(root);
  }

  GuideAnswer decode(String raw) {
    try {
      JsonNode root = json.readTree(raw);
      if (root.path("v").asInt() != VERSION) {
        return null;
      }
      return json.treeToValue(root.get("answer"), GuideAnswer.class);
    } catch (RuntimeException e) {
      log.debug("unreadable guide answer, treated as a miss: {}", e.getClass().getSimpleName());
      return null;
    }
  }
}
