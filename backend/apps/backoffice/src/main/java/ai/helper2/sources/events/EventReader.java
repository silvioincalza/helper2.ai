package ai.helper2.sources.events;

import java.util.Optional;

/**
 * Outbound port: a model that reads one event page (ADR 0021). What it says is checked by {@link
 * EventQuoteGate} before anything is published.
 */
public interface EventReader {

  /** The model's id, kept with every event it read. */
  String model();

  /** A hash of the prompt, kept with every event it read. */
  String promptVersion();

  /**
   * What the model read on [pageText]; empty when its answer is not usable JSON.
   *
   * @throws ai.helper2.scout.LlmUnavailable when no model can be called now (budget, circuit)
   */
  Optional<EventReading> read(String title, String pageText);
}
