package ai.helper2.platform.llm;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

public final class ChatStream {

  static final String REASONING = "reasoningContent";

  private ChatStream() {}

  public record Limits(Duration window, @Nullable Duration firstTextWithin) {

    public static final Limits NONE = new Limits(Duration.ZERO, null);

    public static Limits within(Duration window) {
      return new Limits(window, null);
    }

    public Limits thinkingFor(Duration firstTextWithin) {
      return new Limits(window, firstTextWithin);
    }
  }

  public interface Watch {
    Watch NONE = new Watch() {};

    default void reasoning() {}

    default void writing() {}
  }

  public record Answer(
      String text,
      @Nullable String finishReason,
      @Nullable Integer promptTokens,
      @Nullable Integer completionTokens) {

    public boolean cutAtTheLimit() {
      return CutAnswerWatch.isCut(finishReason);
    }
  }

  public static final class StreamTimeout extends RuntimeException {
    StreamTimeout(Duration window) {
      super("the stream did not end in " + window, new TimeoutException());
    }
  }

  public static final class ThinkingTimeout extends RuntimeException {
    ThinkingTimeout(Duration firstTextWithin) {
      super("the answer text did not start in " + firstTextWithin);
    }
  }

  public static Answer collect(Flux<ChatResponse> chunks, Limits limits, Watch watch) {
    BlockingQueue<Signal<ChatResponse>> signals = new LinkedBlockingQueue<>();
    Assembly assembly = new Assembly(watch);
    Clocked clocked = new Clocked(limits);
    Disposable subscription = chunks.materialize().subscribe(signals::add);
    try {
      while (true) {
        Signal<ChatResponse> signal = clocked.next(signals, assembly.textStarted());
        if (signal.isOnComplete()) {
          return assembly.answer();
        }
        if (signal.isOnError()) {
          throw Exceptions.propagate(signal.getThrowable());
        }
        assembly.add(signal.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Exceptions.propagate(e);
    } finally {
      subscription.dispose();
    }
  }

  private static final class Clocked {
    private final Limits limits;
    private final long started = System.nanoTime();

    Clocked(Limits limits) {
      this.limits = limits;
    }

    Signal<ChatResponse> next(BlockingQueue<Signal<ChatResponse>> signals, boolean textStarted)
        throws InterruptedException {
      boolean thinking = !textStarted && thinkingEndsFirst();
      Duration limit = thinking ? limits.firstTextWithin() : limits.window();
      if (!limit.isPositive()) {
        return signals.take();
      }
      Signal<ChatResponse> signal = signals.poll(left(limit), TimeUnit.NANOSECONDS);
      if (signal != null) {
        return signal;
      }
      throw thinking ? new ThinkingTimeout(limit) : new StreamTimeout(limit);
    }

    private boolean thinkingEndsFirst() {
      Duration thinking = limits.firstTextWithin();
      return thinking != null
          && (!limits.window().isPositive() || thinking.compareTo(limits.window()) < 0);
    }

    private long left(Duration limit) {
      return Math.max(0, limit.toNanos() - (System.nanoTime() - started));
    }
  }

  private static final class Assembly {
    private final Watch watch;
    private final StringBuilder text = new StringBuilder();
    private boolean reasoningStarted;
    private boolean textStarted;
    private @Nullable String finishReason;
    private @Nullable Integer promptTokens;
    private @Nullable Integer completionTokens;

    Assembly(Watch watch) {
      this.watch = watch;
    }

    boolean textStarted() {
      return textStarted;
    }

    void add(@Nullable ChatResponse chunk) {
      if (chunk == null) {
        return;
      }
      countTokens(chunk);
      Generation generation = chunk.getResult();
      if (generation == null) {
        return;
      }
      noteReasoning(generation);
      noteText(generation.getOutput().getText());
      String reason = generation.getMetadata().getFinishReason();
      if (reason != null && !reason.isBlank()) {
        finishReason = reason;
      }
    }

    private void noteReasoning(Generation generation) {
      Object reasoning = generation.getOutput().getMetadata().get(REASONING);
      if (!reasoningStarted && reasoning instanceof String thought && !thought.isBlank()) {
        reasoningStarted = true;
        watch.reasoning();
      }
    }

    private void noteText(@Nullable String piece) {
      if (piece == null || piece.isEmpty()) {
        return;
      }
      text.append(piece);
      if (!textStarted && !piece.isBlank()) {
        textStarted = true;
        watch.writing();
      }
    }

    private void countTokens(ChatResponse chunk) {
      Usage usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
      if (usage == null) {
        return;
      }
      promptTokens = highest(promptTokens, usage.getPromptTokens());
      completionTokens = highest(completionTokens, usage.getCompletionTokens());
    }

    private static @Nullable Integer highest(@Nullable Integer known, @Nullable Integer reported) {
      if (reported == null || reported <= 0) {
        return known;
      }
      return known == null ? reported : Integer.valueOf(Math.max(known, reported));
    }

    Answer answer() {
      return new Answer(text.toString(), finishReason, promptTokens, completionTokens);
    }
  }
}
