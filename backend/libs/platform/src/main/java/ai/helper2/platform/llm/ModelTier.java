package ai.helper2.platform.llm;

/**
 * Which configured chat model a feature calls: {@link #FAST} (Lightning, cheap) for bulk and
 * back-office work, {@link #REASONING} (Super VL) for the steps that decide an answer.
 */
public enum ModelTier {
  FAST,
  REASONING
}
