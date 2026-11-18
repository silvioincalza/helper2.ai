package ai.helper2.identity.usecase;

import ai.helper2.identity.AuditLog;

public class ListAuditEntries {

  private final AuditLog log;

  public ListAuditEntries(AuditLog log) {
    this.log = log;
  }

  public AuditLog.Page list(int page, int size) {
    return log.page(page, size);
  }
}
