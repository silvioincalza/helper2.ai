package ai.helper2.admin.web;

import ai.helper2.admin.config.AdminProperties;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import ai.helper2.platform.config.SpaProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the admin web app (frontend/dist-admin, built under /admin/ and copied to
 * classpath:static/admin/ in the image; ADR 0069). Controllers are matched first, so this only sees
 * what no API route claimed. The cache rules are the edge's (identity's BackOfficeEdge).
 *
 * <ul>
 *   <li>{@code /admin} and every path under it without a file extension: admin.html, so a deep link
 *       or a refresh opens the app.
 *   <li>Existing files under {@code /admin/} (assets, theme.js, favicon): as they are.
 *   <li>{@code /} and {@code /admin/admin.html}: redirect to {@code /admin}, one address for the
 *       app.
 *   <li>{@code /resource/**}: a resource page is the public site's; redirect there.
 * </ul>
 *
 * Without a built admin app (development: Vite serves it) every page is a 404.
 */
@Configuration(proxyBeanMethods = false)
class AdminWebApp implements WebMvcConfigurer {

  private final String location;

  AdminWebApp(SpaProperties spa) {
    String location = spa.location();
    this.location = location.endsWith("/") ? location : location + "/";
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations(location)
        .resourceChain(false)
        .addResolver(new AdminRouteResolver());
  }

  /** The resource handler never serves its own root, so /admin is forwarded to the file. */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/admin").setViewName("forward:/admin/index");
    registry.addViewController("/admin/").setViewName("forward:/admin/index");
  }

  /** An existing file as it is; a page of the app as admin.html; anything else not found. */
  private static final class AdminRouteResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource file = super.getResource(resourcePath, location);
      if (file != null) {
        return file;
      }
      String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
      if (last.contains(".")) {
        return null;
      }
      Resource page = location.createRelative("admin.html");
      return page.isReadable() ? page : null;
    }
  }

  /**
   * A file the admin web app does not have: the same plain 404 as any unknown path, without the
   * path (the generic handler names the file).
   */
  @RestControllerAdvice
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class MissingFile {
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<String> notFound() {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(Problems.json(ProblemType.NOT_FOUND, null));
    }
  }

  /** The redirects of the admin host: relative, never built from Host or X-Forwarded-Host. */
  @Controller
  static class Redirects {

    private final String publicBaseUrl;

    Redirects(AdminProperties admin) {
      String url = admin.publicBaseUrl();
      this.publicBaseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @GetMapping({"/", "/admin/admin.html"})
    ResponseEntity<Void> toTheApp() {
      return found("/admin");
    }

    /** The admin pages link to a resource by its public path; it opens on the public site. */
    @GetMapping("/resource/**")
    ResponseEntity<Void> toThePublicSite(HttpServletRequest request) {
      return found(publicBaseUrl + request.getRequestURI());
    }

    private static ResponseEntity<Void> found(String location) {
      return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
  }
}
