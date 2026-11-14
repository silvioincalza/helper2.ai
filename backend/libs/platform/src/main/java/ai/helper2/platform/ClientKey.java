package ai.helper2.platform;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClientKey {

  private ClientKey() {}

  private static final String IPV6_64 = "::/64";

  private static final int IPV6_56_HEX_DIGITS = 14;

  private static final Pattern IPV4 =
      Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}");

  public static String network(String clientKey) {
    if (clientKey.endsWith(IPV6_64) && clientKey.length() == 16 + IPV6_64.length()) {
      return clientKey.substring(0, IPV6_56_HEX_DIGITS) + "00::/56";
    }
    Matcher v4 = IPV4.matcher(clientKey);
    return v4.matches() ? v4.group(1) + ".0/24" : clientKey;
  }

  public static String of(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      return "unknown";
    }
    String address = remoteAddr.strip();
    if (address.indexOf(':') < 0) {
      return address;
    }
    if (address.startsWith("[") && address.endsWith("]")) {
      address = address.substring(1, address.length() - 1);
    }
    int zone = address.indexOf('%');
    if (zone >= 0) {
      address = address.substring(0, zone);
    }
    try {
      InetAddress parsed = InetAddress.ofLiteral(address);
      if (parsed instanceof Inet6Address) {
        return HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + IPV6_64;
      }
      return parsed.getHostAddress();
    } catch (IllegalArgumentException e) {
      return address;
    }
  }
}
