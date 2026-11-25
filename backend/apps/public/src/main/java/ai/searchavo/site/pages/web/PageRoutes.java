package ai.searchavo.site.pages.web;

import ai.searchavo.platform.ClientRoutes;
import ai.searchavo.site.csp.CspNonce;
import ai.searchavo.site.monetisation.CurrentMonetisation;
import ai.searchavo.site.monetisation.Monetisation;
import ai.searchavo.site.pages.internal.PageHtml;
import ai.searchavo.site.pages.usecase.RenderPage;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
class PageRoutes {

  @Bean
  RouterFunction<ServerResponse> pages(
      RenderPage render, PageHtml html, CurrentMonetisation monetisation) {
    return RouterFunctions.route()
        .route(
            request ->
                (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD)
                    && html.available()
                    && ClientRoutes.owns(request.path()),
            request -> answer(render, html, cacheControl(monetisation.now()), request))
        .build();
  }

  static CacheControl cacheControl(Monetisation monetisation) {
    return monetisation.adsenseOn() ? CacheControl.noStore() : CacheControl.noCache();
  }

  private static ServerResponse answer(
      RenderPage render, PageHtml html, CacheControl cache, ServerRequest request) {
    boolean hasQuery = request.param("q").filter(q -> !q.isBlank()).isPresent();
    RenderPage.Page page = render.render(request.path(), hasQuery);
    if (page.location() != null) {
      return ServerResponse.status(301).location(URI.create(page.location())).build();
    }
    return ServerResponse.status(page.status())
        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
        .cacheControl(cache)
        .body(
            html.render(
                java.util.Objects.requireNonNull(page.head()),
                CspNonce.of(request.servletRequest())));
  }
}
