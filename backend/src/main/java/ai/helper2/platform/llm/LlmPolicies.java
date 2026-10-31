package ai.helper2.platform.llm;

/** The resilience policies of model calls (ADR 0033): one circuit per kind of model the application calls. */
public final class LlmPolicies {

    /** Every chat-model call: guide, query translation, setup guide, Scout enrichment and awesome-list classifier. */
    public static final String CHAT = "llm-chat";

    /** Every embedding call: search queries and the background indexer. */
    public static final String EMBED = "llm-embed";

    private LlmPolicies() {
    }
}
