package ai.helper2.admin.web;

import ai.helper2.admin.config.AdminProperties;
import ai.helper2.admin.config.SpaProperties;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
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

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/admin").setViewName("forward:/admin/index");
    registry.addViewController("/admin/").setViewName("forward:/admin/index");
  }

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

    @GetMapping("/resource/**")
    ResponseEntity<Void> toThePublicSite(HttpServletRequest request) {
      return found(publicBaseUrl + request.getRequestURI());
    }

    private static ResponseEntity<Void> found(String location) {
      return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
  }
}
