package ai.searchavo.research;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface ResearchModel {

  String model();

  Turn next(List<Message> conversation, List<ResearchTool> tools);

  default int callsLeftToday() {
    return Integer.MAX_VALUE;
  }

  sealed interface Message {
    record Instructions(String text) implements Message {}

    record User(String text) implements Message {}

    record Assistant(String text, List<ToolCall> calls) implements Message {}

    record ToolResult(String callId, String name, String text) implements Message {}
  }

  record ToolCall(String id, String name, String arguments) {}

  record Turn(
      String text,
      List<ToolCall> calls,
      @Nullable ResearchAnswer answer,
      @Nullable String parseError,
      int promptTokens,
      int completionTokens) {}

  class Unavailable extends RuntimeException {
    public Unavailable(String message) {
      super(message);
    }
  }

  final class BudgetSpent extends Unavailable {
    public BudgetSpent() {
      super("today's research model calls are used up");
    }
  }
}
