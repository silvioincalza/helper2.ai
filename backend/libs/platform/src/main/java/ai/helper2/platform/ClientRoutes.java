package ai.helper2.platform;

import java.util.List;

public final class ClientRoutes {
  private static final List<String> SERVER_PREFIXES =
      List.of("api/", "oauth2/", "login/", "actuator/");

  private ClientRoutes() {}

  public static boolean owns(String path) {
    String p = path.startsWith("/") ? path.substring(1) : path;
    if (p.equals("logout")
        || p.equals("api")
        || p.equals("oauth2")
        || p.equals("login")
        || p.equals("actuator")
        || SERVER_PREFIXES.stream().anyMatch(p::startsWith)) {
      return false;
    }
    String last = p.substring(p.lastIndexOf('/') + 1);
    return !last.contains(".");
  }
}
