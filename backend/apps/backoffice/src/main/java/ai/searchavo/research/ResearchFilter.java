package ai.searchavo.research;

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
