package ai.helper2.research.usecase;

import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.research.CheckResearch;
import ai.helper2.research.Evidence;
import ai.helper2.research.ResearchAnswer;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchModel;
import ai.helper2.research.ResearchModel.Message;
import ai.helper2.research.ResearchModel.ToolCall;
import ai.helper2.research.ResearchModel.Turn;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import ai.helper2.research.ResearchSubject;
import ai.helper2.research.ResearchTool;
import ai.helper2.scout.UntrustedPageText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RunResearch {

  public static final int MAX_TOOL_CALLS = 12;
  public static final Duration MAX_DURATION = Duration.ofMinutes(3);

  public static final int MAX_TEXT_TO_MODEL = 6_000;
  static final int MAX_URL_TO_MODEL = 2_000;
  static final String NO_TEXT = "the tool returned no readable text";
  static final String NOT_RUN = "not run: the tool calls are used up";
  static final String ANSWER_NOW =
      "You have used all "
          + MAX_TOOL_CALLS
          + " tool calls. Answer now with the JSON object only. Propose only what the evidence you"
          + " have read supports; leave out the rest.";

  private final ResearchRuns runs;
  private final ResearchItems items;
  private final ResearchModel model;
  private final Map<String, ResearchTool> tools;
  private final CheckResearch check;
  private final String instructions;
  private final Function<ResearchSubject, String> subjectText;
  private final Clock clock;

  public RunResearch(
      ResearchRuns runs,
      ResearchItems items,
      ResearchModel model,
      List<ResearchTool> tools,
      CheckResearch check,
      String instructions,
      Function<ResearchSubject, String> subjectText,
      Clock clock) {
    this.runs = runs;
    this.items = items;
    this.model = model;
    this.tools =
        tools.stream()
            .collect(Collectors.toMap(ResearchTool::name, t -> t, (a, b) -> a, LinkedHashMap::new));
    this.check = check;
    this.instructions = instructions;
    this.subjectText = subjectText;
    this.clock = clock;
  }

  public boolean runNext() {
    Optional<ResearchRun> claimed = runs.claimNext(clock.instant());
    claimed.ifPresent(this::run);
    return claimed.isPresent();
  }

  void run(ResearchRun run) {
    Loop loop = new Loop(run);
    try {
      loop.go();
    } catch (ResearchModel.Unavailable e) {
      loop.fail("model unavailable: " + e.getMessage());
    } catch (RuntimeException e) {
      loop.fail("research stopped: " + e.getClass().getSimpleName() + ": " + clip(e.getMessage()));
    }
  }

  private final class Loop {

    private final ResearchRun run;
    private final Instant started;
    private final List<Message> conversation = new ArrayList<>();
    private final Map<String, Evidence> evidence = new LinkedHashMap<>();
    private int seq;
    private int toolCalls;
    private int promptTokens;
    private int completionTokens;
    private int sent;

    Loop(ResearchRun run) {
      this.run = run;
      this.started = clock.instant();
    }

    void go() {
      Optional<ResearchSubject> found = items.subject(run.itemId());
      if (found.isEmpty()) {
        fail("the item is no longer an open resource or course candidate");
        return;
      }
      ResearchSubject subject = found.get();
      conversation.add(new Message.Instructions(instructions));
      conversation.add(new Message.User(subjectText.apply(subject)));
      boolean retried = false;
      boolean answerNow = false;
      while (true) {
        if (runs.isCancelled(run.id())) {
          return;
        }
        if (Duration.between(started, clock.instant()).compareTo(MAX_DURATION) > 0) {
          fail("stopped after " + MAX_DURATION.toMinutes() + " minutes");
          return;
        }
        Turn turn = modelCall(answerNow ? List.of() : List.copyOf(tools.values()));
        if (!turn.calls().isEmpty()) {
          if (answerNow) {
            fail("no final answer after " + MAX_TOOL_CALLS + " tool calls");
            return;
          }
          conversation.add(new Message.Assistant(turn.text(), turn.calls()));
          for (ToolCall call : turn.calls()) {
            if (toolCalls < MAX_TOOL_CALLS) {
              toolCall(call);
            } else {
              notRun(call);
            }
          }
          if (toolCalls >= MAX_TOOL_CALLS) {
            answerNow = true;
            conversation.add(new Message.User(ANSWER_NOW));
          }
          continue;
        }
        ResearchAnswer answer = turn.answer();
        if (answer == null) {
          if (retried) {
            fail("the answer is not the requested JSON: " + turn.parseError());
            return;
          }
          retried = true;
          conversation.add(new Message.Assistant(turn.text(), List.of()));
          conversation.add(
              new Message.User(
                  "Your answer could not be read ("
                      + turn.parseError()
                      + "). Answer again with the JSON object only."));
          continue;
        }
        finish(subject, answer);
        return;
      }
    }

    private Turn modelCall(List<ResearchTool> offered) {
      Instant at = clock.instant();
      String request = render(conversation.subList(sent, conversation.size()));
      sent = conversation.size();
      Turn turn = model.next(List.copyOf(conversation), offered);
      promptTokens += turn.promptTokens();
      completionTokens += turn.completionTokens();
      step(
          ResearchStep.Kind.MODEL_CALL,
          model.model(),
          request,
          render(turn),
          turn.answer() == null && turn.calls().isEmpty() ? turn.parseError() : null,
          at,
          turn.promptTokens(),
          turn.completionTokens());
      return turn;
    }

    private void toolCall(ToolCall call) {
      toolCalls++;
      Instant at = clock.instant();
      ResearchTool tool = tools.get(call.name());
      ResearchTool.Output out;
      String error = null;
      if (tool == null) {
        out = ResearchTool.Output.error("no tool named " + clip(call.name()));
        error = out.text();
      } else {
        try {
          out = tool.call(call.arguments());
        } catch (RuntimeException e) {
          out =
              ResearchTool.Output.error(e.getClass().getSimpleName() + ": " + clip(e.getMessage()));
          error = out.text();
        }
      }
      String text = safe(out.text());
      String toModel;
      if (out.url() != null && error == null && !text.isBlank()) {
        String id = "e" + (evidence.size() + 1);
        evidence.put(id, new Evidence(id, call.name(), out.url(), text));
        toModel =
            "evidenceId="
                + id
                + " url="
                + UntrustedText.untrusted(out.url(), MAX_URL_TO_MODEL)
                + "\n"
                + clipTo(text, MAX_TEXT_TO_MODEL);
      } else {
        toModel = text.isBlank() ? NO_TEXT : clipTo(text, MAX_TEXT_TO_MODEL);
      }
      conversation.add(new Message.ToolResult(call.id(), call.name(), toModel));
      step(
          ResearchStep.Kind.TOOL_CALL,
          call.name(),
          call.arguments(),
          (out.url() == null ? "" : "url: " + out.url() + "\n\n") + out.text(),
          error,
          at,
          null,
          null);
    }

    private void notRun(ToolCall call) {
      Instant at = clock.instant();
      conversation.add(new Message.ToolResult(call.id(), call.name(), NOT_RUN));
      step(
          ResearchStep.Kind.TOOL_CALL,
          call.name(),
          call.arguments(),
          NOT_RUN,
          NOT_RUN,
          at,
          null,
          null);
    }

    private void finish(ResearchSubject subject, ResearchAnswer answer) {
      Instant at = clock.instant();
      ResearchResult result = check.check(subject, answer, evidence);
      step(
          ResearchStep.Kind.CHECK,
          "evidence check",
          answer.attributes().size()
              + " proposed values, "
              + evidence.size()
              + " pieces of evidence",
          renderCheck(result),
          null,
          at,
          null,
          null);
      runs.done(run.id(), result, promptTokens, completionTokens, clock.instant());
    }

    void fail(String error) {
      runs.failed(run.id(), clip(error), promptTokens, completionTokens, clock.instant());
    }

    private void step(
        ResearchStep.Kind kind,
        String name,
        String request,
        String response,
        String error,
        Instant at,
        Integer prompt,
        Integer completion) {
      runs.step(
          new ResearchStep(
              run.id(),
              ++seq,
              kind,
              name,
              ResearchStep.cap(request),
              response == null ? null : ResearchStep.cap(response),
              error,
              Duration.between(at, clock.instant()).toMillis(),
              prompt,
              completion,
              at));
    }
  }

  static String render(List<Message> messages) {
    StringBuilder out = new StringBuilder();
    for (Message m : messages) {
      switch (m) {
        case Message.Instructions i -> out.append("[instructions]\n").append(i.text());
        case Message.User u -> out.append("[user]\n").append(u.text());
        case Message.Assistant a -> out.append("[assistant]\n").append(a.text());
        case Message.ToolResult r ->
            out.append("[tool result ")
                .append(r.name())
                .append(" ")
                .append(r.callId())
                .append("]\n")
                .append(r.text());
      }
      out.append("\n\n");
    }
    return out.toString().strip();
  }

  static String render(Turn turn) {
    StringBuilder out = new StringBuilder(turn.text() == null ? "" : turn.text());
    for (ToolCall c : turn.calls()) {
      out.append("\n[tool call ")
          .append(c.name())
          .append(" ")
          .append(c.id())
          .append("] ")
          .append(c.arguments());
    }
    return out.toString().strip();
  }

  static String renderCheck(ResearchResult r) {
    StringBuilder out = new StringBuilder();
    out.append("verdict: ").append(r.verdict()).append(" — ").append(r.reason()).append('\n');
    for (ResearchResult.Attribute a : r.attributes()) {
      out.append(a.name()).append(": ").append(a.status());
      if (!a.proposed().isEmpty()) {
        out.append(" ").append(a.proposed());
      }
      out.append(" (").append(a.sources().size()).append(" sources)\n");
    }
    for (String d : r.dropped()) {
      out.append("dropped: ").append(d).append('\n');
    }
    return out.toString().strip();
  }

  static String safe(String toolText) {
    String cleaned = UntrustedPageText.INSTANCE.clean(toolText == null ? "" : toolText);
    return UntrustedText.untrusted(cleaned, cleaned.length());
  }

  private static String clip(String s) {
    return clipTo(s == null ? "" : s.replaceAll("\\s+", " "), 300);
  }

  private static String clipTo(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + " […]";
  }
}
