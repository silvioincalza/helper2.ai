package ai.helper2.sources.evidence;

import ai.helper2.scout.DomainRoots;
import java.util.Locale;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.jspecify.annotations.Nullable;

/**
 * Registrable domains from the Public Suffix List that ships with Apache HttpClient 5, already a
 * dependency (ADR 0056: no new library). Both sections count, ICANN and private, so a shared host
 * such as {@code github.io} or {@code vercel.app} is a suffix: {@code acme.github.io} is its own
 * site, not part of {@code github.io}.
 */
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
