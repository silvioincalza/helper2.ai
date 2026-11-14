package ai.helper2.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

@FunctionalInterface
public interface RateLimitKey {

  String ADMIN_HOST_ANONYMOUS = "admin-host";

  String ADMIN_HOST_USER = "admin-host-user-";

  RateLimitKey CLIENT_ADDRESS = HttpServletRequest::getRemoteAddr;

  RateLimitKey SIGNED_IN_ADMIN =
      req -> {
        Principal user = req.getUserPrincipal();
        return user == null ? ADMIN_HOST_ANONYMOUS : ADMIN_HOST_USER + user.getName();
      };

  String of(HttpServletRequest req);
}
