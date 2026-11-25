package ai.searchavo.catalog;

public enum DataOrigin {
  OBSERVED,
  INDEXED,
  CURATED,
  DEMO;

  public boolean rebuildsItsFactsFromItsRecords() {
    return this == OBSERVED || this == INDEXED;
  }
}
