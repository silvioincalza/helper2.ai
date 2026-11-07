package ai.helper2.pages.web;

import ai.helper2.monetisation.Monetisation;
import ai.helper2.pages.internal.PageHtml;
import ai.helper2.pages.usecase.RenderPage;
import ai.helper2.platform.ClientRoutes;
import ai.helper2.platform.CspNonce;
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

/**
 * Every GET (and HEAD) of an address the client router owns (ADR 0029 (part was 0050) §1-2): the
 * shell with the page's head, its status, or a 301. An address with a file extension or a server
 * prefix is never claimed, so robots.txt and the API are as before; the web app's files are the web
 * image's (ADR 0032). Without a shell (development: Vite serves the UI) nothing is claimed and such
 * an address is a 404.
 */
@Configuration(proxyBeanMethods = false)
class PageRoutes {

  @Bean
  RouterFunction<ServerResponse> pages(
      RenderPage render, PageHtml html, Monetisation monetisation) {
    CacheControl cache = cacheControl(monetisation);
    return RouterFunctions.route()
        .route(
            request ->
                (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD)
                    && html.available()
                    && ClientRoutes.owns(request.path()),
            request -> answer(render, html, cache, request))
        .build();
  }

  /**
   * no-cache as before; no-store while AdSense is on (ADR 0030): the page carries this request's
   * CSP nonce, so no cache may give it to anyone else.
   */
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
