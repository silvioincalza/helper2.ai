package ai.helper2.platform;

import java.util.List;

/** Which request paths belong to the client router (the SPA) and which to the server. */
public final class ClientRoutes {

  /** Leading path segments that belong to the server, never to the client router. */
  private static final List<String> SERVER_PREFIXES =
      List.of("api/", "oauth2/", "login/", "actuator/");

  private ClientRoutes() {}

  /** True for paths the client router owns: no file extension and not a server path. */
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
