package ai.helper2.platform;

import ai.helper2.platform.config.RateLimitProperties;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Per-client rate limits keyed on the handler method Spring actually routed the request to, not on
 * the raw request URI. Raw-URI matching can be bypassed with path variants (";param", encoding,
 * duplicate slashes) that still reach the same controller. Counted in Redis under the client's
 * pseudonym of the day, shared by all replicas; in each replica's memory while Redis is down
 * (RedisRateLimiter).
 */
@Configuration(proxyBeanMethods = false)
class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /** Contract operationIds (generated interface method names) that are limited. */
  private final Map<String, RateLimiter> limiters;

  private final StringRedisTemplate redis;
  private final QuotaKeys keys;
  private final Resilience resilience;
  private final Clock clock;
  private final MeterRegistry meters;
  private final AdminHost adminHost;

  RateLimitInterceptor(
      Clock clock,
      ObjectProvider<StringRedisTemplate> redis,
      QuotaKeys keys,
      Resilience resilience,
      MeterRegistry meters,
      RateLimitProperties limits,
      AdminHost adminHost) {
    this.redis = redis.getIfAvailable();
    this.keys = keys;
    this.resilience = resilience;
    this.clock = clock;
    this.meters = meters;
    this.adminHost = adminHost;
    // Each guide question costs three LLM calls: keep it tighter than search. The guide and the
    // tool guide share one limiter, so switching endpoint does not double a client's budget.
    RateLimiter guide = limiter("guide", limits.guidePerMinute(), Duration.ofMinutes(1));
    // Home page reads: cheap index reads, limited only so they cannot be looped against the
    // database. They share one limiter: one home page view makes both.
    RateLimiter home = limiter("recent", limits.recentPerMinute(), Duration.ofMinutes(1));
    this.limiters =
        Map.of(
            "search",
            limiter("search", limits.searchPerMinute(), Duration.ofMinutes(1)),
            "reportResource",
            limiter("report", limits.reportPerHour(), Duration.ofHours(1)),
            "askGuideStream",
            guide,
            // The tool guide can call a model too.
            "askResourceGuide",
            guide,
            // Can fetch a README from GitHub (on the sources' token) when the server has no
            // registry entry.
            "getResourceSetup",
            limiter("setup", limits.searchPerMinute(), Duration.ofMinutes(1)),
            "listRecent",
            home,
            "listHomeExamples",
            home,
            // The numbers of the text pages: one read per page view, as cheap as the home reads.
            "getPolicy",
            limiter("policy", limits.recentPerMinute(), Duration.ofMinutes(1)),
            // The category list (ADR 0053): one read per results page, then cached by the browser.
            "listCategories",
            limiter("categories", limits.recentPerMinute(), Duration.ofMinutes(1)));
  }

  private RateLimiter limiter(String name, int limit, Duration window) {
    RateLimiter inMemory = new InMemoryRateLimiter(limit, window, clock);
    return redis == null
        ? inMemory
        : new RedisRateLimiter(name, limit, window, keys, redis, resilience, inMemory, meters);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
      throws IOException {
    // An async handler (the streamed guide) is dispatched again when it completes: charge only the
    // first dispatch.
    if (!(handler instanceof HandlerMethod hm) || req.getDispatcherType() == DispatcherType.ASYNC) {
      return true;
    }
    RateLimiter limiter = limiters.get(hm.getMethod().getName());
    if (limiter == null) {
      return true;
    }
    long wait = limiter.acquire(clientKey(req));
    if (wait == 0) {
      return true;
    }
    res.setStatus(429);
    res.setHeader("Retry-After", Long.toString(wait));
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.getWriter()
        .write(
            Problems.json(
                ProblemType.RATE_LIMITED,
                "Rate limit reached. Try again in " + wait + " seconds."));
    return false;
  }

  /**
   * Who the limit counts for. On the public host, the client address: it honours X-Forwarded-For
   * only when H2_FORWARD_HEADERS_STRATEGY is set behind a trusted proxy. On the admin host there is
   * no Cloudflare in front, so a forwarded address could be the client's own choice (ADR 0035): the
   * key is the signed-in administrator, and one shared key for whoever is not signed in yet. The
   * limit still holds there, but nothing a client sends can reset it.
   */
  private String clientKey(HttpServletRequest req) {
    if (!adminHost.currentRequestOnAdminHost()) {
      return req.getRemoteAddr();
    }
    Principal user = req.getUserPrincipal();
    return user == null ? ADMIN_HOST_ANONYMOUS : ADMIN_HOST_USER + user.getName();
  }

  /** Neither looks like an address: {@link ClientKey} keeps them as they are. */
  static final String ADMIN_HOST_ANONYMOUS = "admin-host";

  static final String ADMIN_HOST_USER = "admin-host-user-";
}
