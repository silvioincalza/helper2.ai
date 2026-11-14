package ai.helper2.catalog;

public enum DataOrigin {
  OBSERVED,
  INDEXED,
  CURATED,
  DEMO;

  public boolean rebuildsItsFactsFromItsRecords() {
    return this == OBSERVED || this == INDEXED;
  }
}
