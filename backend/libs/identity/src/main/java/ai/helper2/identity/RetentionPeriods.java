package ai.helper2.identity;

import java.time.Duration;

/** How long identity keeps its data (ADR 0034). */
public interface RetentionPeriods {

  /** An account nobody signed in to for this long goes, with its bookmarks. */
  Duration inactiveAccounts();

  /** An admin audit entry older than this goes. */
  Duration adminAudit();
}
