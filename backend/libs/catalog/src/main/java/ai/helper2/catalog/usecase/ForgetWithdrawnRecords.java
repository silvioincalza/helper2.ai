package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CatalogWriter.Forgotten;
import java.util.Collection;

public class ForgetWithdrawnRecords {

  private final CatalogWriter writer;

  public ForgetWithdrawnRecords(CatalogWriter writer) {
    this.writer = writer;
  }

  public Forgotten forget(String sourceId, Collection<String> externalIds) {
    return externalIds.isEmpty() ? Forgotten.NONE : writer.forgetRecords(sourceId, externalIds);
  }
}
