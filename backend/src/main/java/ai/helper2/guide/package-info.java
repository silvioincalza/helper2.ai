/**
 * AI guide: turns a need into recommendations from real search results, with quotes and an optional
 * prompt. Retrieval is deterministic (search module); NVIDIA Nemotron on Nebius Token Factory only
 * chooses among retrieved candidates, and GuideValidator decides what reaches the user.
 */
@org.springframework.modulith.ApplicationModule(displayName = "AI guide")
package ai.helper2.guide;
