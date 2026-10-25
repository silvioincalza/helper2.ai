package ai.helper2.platform;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

/**
 * Who asks, for the per-client rate limits and the guide's daily quota: the remote address, IPv4 as is, IPv6
 * grouped by its /64 prefix (zone id dropped; IPv4-mapped becomes IPv4), because one subscriber usually holds a
 * whole /64 and could otherwise rotate addresses to reset a limit.
 */
public final class ClientKey {

    private ClientKey() {
    }

    /** Never null: an address that is missing is "unknown", one that does not parse is kept as it is. */
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
            // A literal only: never a DNS lookup.
            InetAddress parsed = InetAddress.ofLiteral(address);
            if (parsed instanceof Inet6Address) {
                return HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "::/64";
            }
            return parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            return address;
        }
    }
}
