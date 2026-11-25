package ai.searchavo.sources.evidence;

import ai.searchavo.scout.DomainRoots;
import java.util.Locale;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.jspecify.annotations.Nullable;

public class PublicSuffixDomains implements DomainRoots {

  private final PublicSuffixMatcher matcher = PublicSuffixMatcherLoader.getDefault();

  @Override
  public @Nullable String registrable(String host) {
    if (host == null || host.isBlank()) {
      return null;
    }
    String root = matcher.getDomainRoot(host.toLowerCase(Locale.ROOT));
    return root == null || root.isBlank() ? null : root;
  }
}
