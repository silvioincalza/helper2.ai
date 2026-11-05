package ai.helper2.pages.web;

import ai.helper2.pages.internal.PageHtml;
import ai.helper2.pages.usecase.RenderPage;
import ai.helper2.platform.ClientRoutes;
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
 * Every GET of an address the client router owns (ADR 0050 §1-2): the shell with the page's head,
 * its status, or a 301. Router functions are asked before the static files, and an address with a
 * file extension or a server prefix is never claimed, so assets, robots.txt and the API are as
 * before. Without a built app nothing is claimed: SinglePageApp answers as it always did.
 */
@Configuration(proxyBeanMethods = false)
class PageRoutes {

  @Bean
  RouterFunction<ServerResponse> pages(RenderPage render, PageHtml html) {
    return RouterFunctions.route()
        .route(
            request ->
                request.method() == HttpMethod.GET
                    && html.available()
                    && ClientRoutes.owns(request.path()),
            request -> answer(render, html, request))
        .build();
  }

  private static ServerResponse answer(RenderPage render, PageHtml html, ServerRequest request) {
    boolean hasQuery = request.param("q").filter(q -> !q.isBlank()).isPresent();
    RenderPage.Page page = render.render(request.path(), hasQuery);
    if (page.location() != null) {
      return ServerResponse.status(301).location(URI.create(page.location())).build();
    }
    return ServerResponse.status(page.status())
        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noCache())
        .body(html.render(java.util.Objects.requireNonNull(page.head())));
  }
}
