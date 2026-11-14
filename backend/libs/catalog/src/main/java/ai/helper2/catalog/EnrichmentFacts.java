package ai.helper2.catalog;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface EnrichmentFacts {
  List<ResourceDetail.Assertion> assertions(UUID resourceId);

  List<ResourceDetail.Relationship> relationships(UUID resourceId);

  ResourceDetail.@Nullable Summary summary(UUID resourceId);
}
