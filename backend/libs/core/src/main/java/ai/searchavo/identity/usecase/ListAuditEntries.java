package ai.searchavo.identity.usecase;

import ai.searchavo.identity.AuditLog;

public class ListAuditEntries {

  private final AuditLog log;

  public ListAuditEntries(AuditLog log) {
    this.log = log;
  }

  public AuditLog.Page list(int page, int size) {
    return log.page(page, size);
  }
}
