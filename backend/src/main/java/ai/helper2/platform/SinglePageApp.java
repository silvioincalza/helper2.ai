package ai.helper2.platform;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built SPA (frontend/dist, copied to classpath:static/ in the image). Controllers are matched
 * first, so this only sees what no API route claimed.
 * <ul>
 *   <li>{@code /assets/**}: Vite's content-hashed files, cached for a year as immutable.</li>
 *   <li>Any other existing file (favicon, robots.txt): served, revalidated on each use.</li>
 *   <li>Any other GET without a file extension, outside {@code /api/**}, {@code /oauth2/**},
 *       {@code /login/**}, {@code /logout} and {@code /actuator/**}: index.html with {@code no-cache}, so a
 *       direct visit or a refresh of any client route loads the app and a new release is picked up at once.</li>
 * </ul>
 * Without a built SPA (development: Vite serves the UI) every such path is a 404. Spring Boot's own static
 * mapping is off ({@code spring.web.resources.add-mappings=false}) so these rules are the only ones.
 */
@Configuration(proxyBeanMethods = false)
class SinglePageApp implements WebMvcConfigurer {

    /** Leading path segments that belong to the server, never to the client router. */
    private static final List<String> SERVER_PREFIXES = List.of("api/", "oauth2/", "login/", "actuator/");

    private final String location;

    SinglePageApp(@Value("${helper2.spa.location:classpath:/static/}") String location) {
        this.location = location.endsWith("/") ? location : location + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations(location + "assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
            .addResourceLocations(location)
            .setCacheControl(CacheControl.noCache())
            .resourceChain(false)
            .addResolver(new ClientRoutes());
    }

    /** The resource handler never serves an empty path, so the root is forwarded to the file explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /** True for paths the client router owns: no file extension and not a server path. */
    static boolean isClientRoute(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.equals("logout") || p.equals("api") || p.equals("oauth2") || p.equals("login") || p.equals("actuator")
            || SERVER_PREFIXES.stream().anyMatch(p::startsWith)) {
            return false;
        }
        String last = p.substring(p.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    /** An existing file as it is; a client route as index.html; anything else not found. */
    private static final class ClientRoutes extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null || !isClientRoute(resourcePath)) {
                return file;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
