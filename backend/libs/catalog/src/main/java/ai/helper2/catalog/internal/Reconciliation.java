package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import java.util.Collection;
import java.util.UUID;

interface Reconciliation {

  void reconcile(Collection<UUID> resources, DataOrigin writtenBy);
}
