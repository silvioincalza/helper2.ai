package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.platform.llm.ChatStream.Answer;
import ai.helper2.platform.llm.ChatStream.Limits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

class ChatStreamTest {

  static final Duration SHORT = Duration.ofMillis(150);
  static final Duration LONG = Duration.ofSeconds(20);

  final AtomicBoolean disposed = new AtomicBoolean();
  final List<String> seen = new ArrayList<>();
  final ChatStream.Watch watch =
      new ChatStream.Watch() {
        @Override
        public void reasoning() {
          seen.add("reasoning");
        }

        @Override
        public void writing() {
          seen.add("writing");
        }
      };

  static ChatResponse chunk(String text, String reasoningSoFar, String finishReason) {
    return chunk(text, reasoningSoFar, finishReason, 0, 0);
  }

  static ChatResponse chunk(
      String text, String reasoningSoFar, String finishReason, int promptTokens, int completion) {
    AssistantMessage message =
        AssistantMessage.builder()
            .content(text)
            .properties(Map.of(ChatStream.REASONING, reasoningSoFar))
            .build();
    return new ChatResponse(
        List.of(
            new Generation(
                message, ChatGenerationMetadata.builder().finishReason(finishReason).build())),
        ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completion)).build());
  }

  static ChatResponse text(String piece) {
    return chunk(piece, "", null);
  }

  static ChatResponse thought(String soFar) {
    return chunk("", soFar, null);
  }

  Flux<ChatResponse> thenSilent(ChatResponse... chunks) {
    return Flux.just(chunks).concatWith(Flux.never()).doOnCancel(() -> disposed.set(true));
  }

  @Test
  void theAnswerIsTheTextOfEveryChunkInOrder() {
    Answer answer =
        ChatStream.collect(
            Flux.just(text(""), text("{\"a\":"), text("1}")), Limits.NONE, ChatStream.Watch.NONE);

    assertThat(answer.text()).isEqualTo("{\"a\":1}");
  }

  @Test
  void theFinishReasonIsTheLastOneAChunkGave() {
    Answer answer =
        ChatStream.collect(
            Flux.just(text("{"), chunk("", "", "LENGTH"), text("")),
            Limits.NONE,
            ChatStream.Watch.NONE);

    assertThat(answer.finishReason()).isEqualTo("LENGTH");
    assertThat(answer.cutAtTheLimit()).isTrue();
  }

  @Test
  void anAnswerThatStoppedOnItsOwnIsNotCut() {
    Answer answer =
        ChatStream.collect(Flux.just(chunk("{}", "", "STOP")), Limits.NONE, ChatStream.Watch.NONE);

    assertThat(answer.cutAtTheLimit()).isFalse();
  }

  @Test
  void theTokenCountsAreTheHighestAnyChunkReported() {
    Answer answer =
        ChatStream.collect(
            Flux.just(chunk("", "", null, 120, 0), text("{}"), chunk("", "", null, 120, 45)),
            Limits.NONE,
            ChatStream.Watch.NONE);

    assertThat(answer.promptTokens()).isEqualTo(120);
    assertThat(answer.completionTokens()).isEqualTo(45);
  }

  @Test
  void aCountNoChunkReportedIsUnknownNotZero() {
    Answer answer =
        ChatStream.collect(
            Flux.just(chunk("", "", null, 120, 0), text("{}")), Limits.NONE, ChatStream.Watch.NONE);

    assertThat(answer.promptTokens()).isEqualTo(120);
    assertThat(answer.completionTokens()).isNull();
  }

  @Test
  void reasoningAndWritingAreEachReportedOnceWhenTheyStart() {
    ChatStream.collect(
        Flux.just(text(""), thought("Let"), thought("Let me"), text("{"), text("}")),
        Limits.NONE,
        watch);

    assertThat(seen).containsExactly("reasoning", "writing");
  }

  @Test
  void anAnswerWithoutReasoningReportsOnlyWriting() {
    ChatStream.collect(Flux.just(text("{}")), Limits.NONE, watch);

    assertThat(seen).containsExactly("writing");
  }

  @Test
  void aStreamStillOpenWhenItsWindowEndsIsDisposedAndFailsAsATimeoutOfTheProvider() {
    Throwable failure =
        catchThrowable(
            () -> ChatStream.collect(thenSilent(text("{")), Limits.within(SHORT), watch));

    assertThat(failure).isInstanceOf(ChatStream.StreamTimeout.class);
    assertThat(ModelErrors.category(failure)).isEqualTo("timeout");
    assertThat(ModelErrors.isProviderError(failure)).isTrue();
    assertThat(disposed).isTrue();
  }

  @Test
  void anInterruptDisposesTheStreamAndKeepsTheInterrupt() {
    Thread.currentThread().interrupt();

    Throwable failure =
        catchThrowable(() -> ChatStream.collect(thenSilent(), Limits.within(LONG), watch));

    assertThat(ModelErrors.isInterrupt(failure)).isTrue();
    assertThat(Thread.interrupted()).isTrue();
    assertThat(disposed).isTrue();
  }

  @Test
  void aFailureOfTheStreamAfterItsFirstChunkIsThrownAsItIs() {
    IllegalStateException broken = new IllegalStateException("connection reset");

    Throwable failure =
        catchThrowable(() ->
            ChatStream.collect(
                Flux.just(text("{")).concatWith(Flux.error(broken)), Limits.NONE, watch));

    assertThat(failure).isSameAs(broken);
    assertThat(seen).containsExactly("writing");
  }

  @Test
  void thinkingWithNoAnswerTextInItsTimeIsDisposedAndIsNotTheProvidersFailure() {
    Throwable failure =
        catchThrowable(() ->
            ChatStream.collect(
                thenSilent(thought("Let me")), Limits.within(LONG).thinkingFor(SHORT), watch));

    assertThat(failure).isInstanceOf(ChatStream.ThinkingTimeout.class);
    assertThat(ModelErrors.isProviderError(failure)).isFalse();
    assertThat(ModelErrors.category(failure)).isEqualTo("timeout");
    assertThat(disposed).isTrue();
  }

  @Test
  void anAnswerThatStartedInTheThinkingTimeHasTheWholeWindow() {
    Flux<ChatResponse> startsThenTakesLonger =
        Flux.just(thought("Let me"), text("{"))
            .concatWith(Flux.just(text("}")).delaySequence(SHORT.multipliedBy(2)));

    Answer answer =
        ChatStream.collect(startsThenTakesLonger, Limits.within(LONG).thinkingFor(SHORT), watch);

    assertThat(answer.text()).isEqualTo("{}");
  }

  @Test
  void aWindowShorterThanTheThinkingTimeEndsTheStreamAsATimeout() {
    Throwable failure =
        catchThrowable(() ->
            ChatStream.collect(
                thenSilent(thought("Let me")), Limits.within(SHORT).thinkingFor(LONG), watch));

    assertThat(failure).isInstanceOf(ChatStream.StreamTimeout.class);
  }
}
