package ai.helper2.catalog;

import java.util.Collection;
import java.util.Map;

public interface KnownTargets {

  Map<String, KnownTarget> named(Collection<String> targets);
}
