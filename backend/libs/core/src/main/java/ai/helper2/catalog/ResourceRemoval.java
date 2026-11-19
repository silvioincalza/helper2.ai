package ai.helper2.catalog;

import java.util.Optional;
import java.util.UUID;

public interface ResourceRemoval {

  record Removed(String canonicalUrl) {}

  Optional<Removed> remove(UUID resourceId);
}
