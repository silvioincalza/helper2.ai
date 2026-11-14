package ai.helper2.platform.llm;

import java.util.Optional;
import java.util.regex.Pattern;

public final class ModelAnswers {
  private static final Pattern REASONING = Pattern.compile("(?s)<think>.*?(</think>|\\z)");

  private ModelAnswers() {}

  public static String withoutReasoning(String answer) {
    return answer == null ? "" : REASONING.matcher(answer).replaceAll("").strip();
  }

  public static Optional<String> jsonObject(String answer) {
    String text = withoutReasoning(answer);
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    return start < 0 || end < start
        ? Optional.empty()
        : Optional.of(text.substring(start, end + 1));
  }
}
