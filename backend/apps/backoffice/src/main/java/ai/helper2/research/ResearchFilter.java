package ai.helper2.research;

public enum ResearchFilter {
  KEEP,
  REJECT,
  DUPLICATE,
  ACTIVE,
  NONE;

  public boolean excludes() {
    return this == NONE;
  }
}
