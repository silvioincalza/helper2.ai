package ai.helper2.platform.llm;

import java.util.ArrayList;
import java.util.List;

public final class NvidiaOnly {

  static final String PREFIX = "nvidia/";

  static final String PROVIDER_CLIENT = "openai";

  private NvidiaOnly() {}

  public static void require(
      String chatProvider,
      String chatModel,
      String fastModel,
      String reasoningModel,
      String embeddingKey,
      String embeddingModel) {
    List<String> wrong = new ArrayList<>();
    if (PROVIDER_CLIENT.equals(chatProvider)) {
      check("spring.ai.openai.chat.model", chatModel, wrong);
      check("helper2.llm.fast-model", fastModel, wrong);
      check("helper2.llm.reasoning-model", reasoningModel, wrong);
    }
    if (!isBlank(embeddingKey)) {
      check("helper2.embedding.model", embeddingModel, wrong);
    }
    if (!wrong.isEmpty()) {
      throw new IllegalStateException(
          "only NVIDIA models (" + PREFIX + "...) may be configured: " + String.join(", ", wrong));
    }
  }

  private static void check(String property, String id, List<String> wrong) {
    if (!isBlank(id) && !id.strip().startsWith(PREFIX)) {
      wrong.add(property + "=" + id.strip());
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
