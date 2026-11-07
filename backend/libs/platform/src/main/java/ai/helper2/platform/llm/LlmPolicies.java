package ai.helper2.platform.llm;

/**
 * The resilience policies of model calls (ADR 0033): one circuit per kind of model the application
 * calls.
 */
public final class LlmPolicies {

  /**
   * Every chat-model call: guide, query translation, setup guide, Scout enrichment and awesome-list
   * classifier.
   */
  public static final String CHAT = "llm-chat";

  /** Embedding calls of search queries and guide questions. */
  public static final String EMBED = "llm-embed";

  /**
   * Embedding calls of the background indexer: its own circuit, so failed batches never refuse a
   * visitor's search query.
   */
  public static final String EMBED_INDEX = "llm-embed-index";

  private LlmPolicies() {}
}
