package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.DataOrigin;
import java.util.Collection;
import java.util.UUID;

interface Reconciliation {

  void reconcile(Collection<UUID> resources, DataOrigin writtenBy);
}
