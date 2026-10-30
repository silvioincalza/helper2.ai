package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/** Every client route loads the SPA on a direct visit or a refresh; server paths and missing files stay 404. */
class SinglePageAppTest {

    private static final Pattern ROUTE = Pattern.compile("path: '([^'*]+)'");

    @TempDir
    Path dist;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class Mvc {
        @Bean
        InternalResourceViewResolver views() {
            return new InternalResourceViewResolver();
        }

        @Bean
        Api api() {
            return new Api();
        }
    }

    @RestController
    static class Api {
        @GetMapping("/api/v1/ping")
        String ping() {
            return "pong";
        }
    }

    @BeforeEach
    void start() throws IOException {
        Files.writeString(dist.resolve("index.html"), "<!doctype html><title>Helper2</title>");
        Files.writeString(dist.resolve("robots.txt"), "User-agent: *");
        Files.createDirectories(dist.resolve("assets"));
        Files.writeString(dist.resolve("assets/index-3f2a1b.js"), "console.log(1)");
        mvc = mvcServing(dist.toUri().toString());
    }

    private MockMvc mvcServing(String location) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("test", Map.of("helper2.spa.location", location)));
        context.register(Mvc.class, SinglePageApp.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void everyFrontendRouteServesIndexWithoutCaching() throws Exception {
        List<String> routes = frontendRoutes();
        assertThat(routes).contains("sources", "guide", "mission");
        for (String route : routes) {
            mvc.perform(get("/" + route.replace(":id", "8a0e6c1e-2f43-5b6e-9d0a-1f1e2d3c4b5a")))
                .andExpect(status().isOk())
                .andExpect(content().string("<!doctype html><title>Helper2</title>"))
                .andExpect(header().string("Cache-Control", "no-cache"));
        }
    }

    @Test
    void routesTheBuildDoesNotKnowYetAlsoLoadTheApp() throws Exception {
        mvc.perform(get("/some/new/route")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void theRootForwardsToIndex() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void hashedAssetsAreImmutable() throws Exception {
        mvc.perform(get("/assets/index-3f2a1b.js"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void otherFilesAreServedAndRevalidated() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void missingFilesAndServerPathsAreNotTheApp() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        for (String path : List.of("/api/v1/unknown", "/oauth2/authorization/github", "/login/oauth2/code/github", "/logout",
            "/actuator/health")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/ping")).andExpect(status().isOk()).andExpect(content().string("pong"));
    }

    @Test
    void notFoundWhenTheSpaIsNotBuilt() throws Exception {
        stop();
        MockMvc dev = mvcServing(dist.resolve("missing").toUri().toString());
        dev.perform(get("/sources")).andExpect(status().isNotFound());
    }

    @Test
    void clientRoutesHaveNoExtensionAndAreNotServerPaths() {
        assertThat(SinglePageApp.isClientRoute("resource/abc")).isTrue();
        assertThat(SinglePageApp.isClientRoute("/sources")).isTrue();
        assertThat(SinglePageApp.isClientRoute("apiary")).isTrue();
        assertThat(SinglePageApp.isClientRoute("api/v1/search")).isFalse();
        assertThat(SinglePageApp.isClientRoute("logout")).isFalse();
        assertThat(SinglePageApp.isClientRoute("assets/app.js")).isFalse();
    }

    private static List<String> frontendRoutes() throws IOException {
        String routes = Files.readString(Path.of("../frontend/src/routes.tsx"));
        return ROUTE.matcher(routes).results().map(m -> m.group(1)).toList();
    }
}
