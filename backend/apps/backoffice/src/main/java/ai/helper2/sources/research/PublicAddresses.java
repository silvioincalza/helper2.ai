package ai.helper2.sources.research;

import ai.helper2.search.ShadowLibraries;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class PublicAddresses {

  private final ShadowLibraries shadowLibraries;

  PublicAddresses(ShadowLibraries shadowLibraries) {
    this.shadowLibraries = shadowLibraries;
  }

  @Nullable String problem(String url) {
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (IllegalArgumentException e) {
      return "not a URL";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      return "only http and https";
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return "no host";
    }
    if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
      return "only ports 80 and 443";
    }
    if (shadowLibraries.isShadowLibrary(host)) {
      return "a shadow library is never read";
    }
    try {
      for (InetAddress a : InetAddress.getAllByName(host)) {
        if (!isPublic(a)) {
          return "a private or local address";
        }
      }
    } catch (UnknownHostException e) {
      return "unknown host";
    }
    return null;
  }

  static boolean isPublic(InetAddress a) {
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return false;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet6Address) {
      return (b[0] & 0xfe) != 0xfc;
    }
    int first = b[0] & 0xff;
    int second = b[1] & 0xff;
    return !(first == 100 && second >= 64 && second <= 127) && first != 0;
  }
}
