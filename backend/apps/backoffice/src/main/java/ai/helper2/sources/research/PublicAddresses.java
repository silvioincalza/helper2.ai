package ai.helper2.sources.research;

import ai.helper2.search.ShadowLibraries;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class PublicAddresses {

  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  static final class NotPublic extends Exception {
    NotPublic(String reason) {
      super(reason);
    }
  }

  private final ShadowLibraries shadowLibraries;
  private final Resolver resolver;

  PublicAddresses(ShadowLibraries shadowLibraries) {
    this(shadowLibraries, InetAddress::getAllByName);
  }

  PublicAddresses(ShadowLibraries shadowLibraries, Resolver resolver) {
    this.shadowLibraries = shadowLibraries;
    this.resolver = resolver;
  }

  @Nullable String problem(String url) {
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (IllegalArgumentException e) {
      return "not a URL";
    }
    String problem = urlProblem(uri);
    if (problem != null) {
      return problem;
    }
    try {
      resolve(uri.getHost());
      return null;
    } catch (NotPublic e) {
      return e.getMessage();
    }
  }

  @Nullable String urlProblem(URI uri) {
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
    return null;
  }

  List<InetAddress> resolve(String host) throws NotPublic {
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new NotPublic("unknown host");
    }
    if (addresses == null || addresses.length == 0) {
      throw new NotPublic("unknown host");
    }
    for (InetAddress a : addresses) {
      if (!isPublic(a)) {
        throw new NotPublic("a private or local address");
      }
    }
    return List.of(addresses);
  }

  static boolean isPublic(InetAddress a) {
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return false;
    }
    if (a instanceof Inet6Address v6) {
      return isPublicV6(v6);
    }
    return isPublicV4(a.getAddress());
  }

  private static boolean isPublicV6(Inet6Address a) {
    byte[] b = a.getAddress();
    if ((b[0] & 0xfe) == 0xfc) {
      return false;
    }
    if (isTeredo(b) || isNat64(b) && !zero(b, 4, 12)) {
      return false;
    }
    byte[] embedded = embeddedV4(a, b);
    return embedded == null || isPublic(v4(embedded));
  }

  private static byte @Nullable [] embeddedV4(Inet6Address a, byte[] b) {
    if (a.isIPv4CompatibleAddress() || isNat64(b)) {
      return new byte[] {b[12], b[13], b[14], b[15]};
    }
    if (isSixToFour(b)) {
      return new byte[] {b[2], b[3], b[4], b[5]};
    }
    if (isMapped(b)) {
      return new byte[] {b[12], b[13], b[14], b[15]};
    }
    return null;
  }

  private static boolean isTeredo(byte[] b) {
    return b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0;
  }

  private static boolean isSixToFour(byte[] b) {
    return b[0] == 0x20 && b[1] == 0x02;
  }

  private static boolean isNat64(byte[] b) {
    return b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
  }

  private static boolean isMapped(byte[] b) {
    return zero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
  }

  private static boolean zero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static InetAddress v4(byte[] b) {
    try {
      return Inet4Address.getByAddress(b);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isPublicV4(byte[] b) {
    int first = b[0] & 0xff;
    int second = b[1] & 0xff;
    int third = b[2] & 0xff;
    if (first == 0 || first == 10 || first == 127 || first >= 224) {
      return false;
    }
    if (first == 100 && second >= 64 && second <= 127) {
      return false;
    }
    if (first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31) {
      return false;
    }
    if (first == 192 && (second == 168 || second == 0 && (third == 0 || third == 2))) {
      return false;
    }
    if (first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)) {
      return false;
    }
    return !(first == 203 && second == 0 && third == 113);
  }
}
