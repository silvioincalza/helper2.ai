package ai.helper2.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The admin port (ADR 0035): a second connector that only the in-network admin host reaches. When it is set, the
 * back office answers only there, and only a sign-in made there carries ADMIN. Not set (0): one port, as before.
 */
record AdminPort(int port) {

    boolean enabled() {
        return port > 0;
    }

    /** True on the admin port, and on every request when there is no admin port. */
    boolean admits(HttpServletRequest request) {
        return !enabled() || request.getLocalPort() == port;
    }

    /** The request being served on this thread is on the admin port (true without one, false without a request). */
    boolean currentRequestAdmitted() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return Optional.ofNullable(attributes)
            .filter(ServletRequestAttributes.class::isInstance)
            .map(a -> admits(((ServletRequestAttributes) a).getRequest()))
            .orElse(!enabled());
    }

    /**
     * The back office: the admin page and its API. Checked on the decoded, normalised path the servlet container
     * maps (servlet path + path info), so "%61dmin" or "x/../admin" cannot pass as another path.
     */
    static boolean isBackOffice(HttpServletRequest request) {
        String path = request.getServletPath() + Optional.ofNullable(request.getPathInfo()).orElse("");
        return under(path, "/admin") || under(path, "/api/v1/admin");
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
