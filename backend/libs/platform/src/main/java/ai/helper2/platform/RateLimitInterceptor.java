package ai.helper2.platform;

import ai.helper2.platform.config.RateLimitProperties;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.HandlerFunction;

@Configuration(proxyBeanMethods = false)
class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {
  static final String EVERYTHING_ELSE = "default";
  static final Map<String, String> OWN_LIMIT =
      Map.ofEntries(
          Map.entry("search", "search"),
          Map.entry("reportResource", "report"),
          Map.entry("askGuideStream", "guide"),
          Map.entry("askResourceGuide", "guide"),
          Map.entry("getResourceSetup", "setup"),
          Map.entry("listRecent", "recent"),
          Map.entry("listHomeExamples", "recent"),
          Map.entry("getPolicy", "policy"),
          Map.entry("getSite", "site"),
          Map.entry("listCategories", "categories"));
  static final Set<String> LIMITED_OUTSIDE_THE_API = Set.of("sitemap");

  private final Map<String, RateLimiter> limiters;

  private final StringRedisTemplate redis;
  private final QuotaKeys keys;
  private final Resilience resilience;
  private final Clock clock;
  private final MeterRegistry meters;
  private final RateLimitKey clientKey;

  RateLimitInterceptor(
      Clock clock,
      ObjectProvider<StringRedisTemplate> redis,
      QuotaKeys keys,
      Resilience resilience,
      MeterRegistry meters,
      RateLimitProperties limits,
      RateLimitKey clientKey) {
    this.redis = redis.getIfAvailable();
    this.keys = keys;
    this.resilience = resilience;
    this.clock = clock;
    this.meters = meters;
    this.clientKey = clientKey;
    Duration minute = Duration.ofMinutes(1);
    this.limiters =
        Map.of(
            "search",
            limiter("search", limits.searchPerMinute(), minute),
            "report",
            limiter("report", limits.reportPerHour(), Duration.ofHours(1)),
            "guide",
            limiter("guide", limits.guidePerMinute(), minute),
            "setup",
            limiter("setup", limits.searchPerMinute(), minute),
            "recent",
            limiter("recent", limits.recentPerMinute(), minute),
            "policy",
            limiter("policy", limits.recentPerMinute(), minute),
            "site",
            limiter("site", limits.recentPerMinute(), minute),
            "categories",
            limiter("categories", limits.recentPerMinute(), minute),
            EVERYTHING_ELSE,
            limiter(EVERYTHING_ELSE, limits.defaultPerMinute(), minute));
  }

  private RateLimiter limiter(String name, int limit, Duration window) {
    RateLimiter inMemory = new InMemoryRateLimiter(limit, window, clock);
    return redis == null
        ? inMemory
        : new RedisRateLimiter(name, limit, window, keys, redis, resilience, inMemory, meters);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
      throws IOException {
    if (req.getDispatcherType() != DispatcherType.REQUEST) {
      return true;
    }
    RateLimiter limiter = limiterOf(handler, req);
    if (limiter == null) {
      return true;
    }
    long wait = limiter.acquire(clientKey.of(req));
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

  private RateLimiter limiterOf(Object handler, HttpServletRequest req) {
    if (handler instanceof HandlerFunction<?>) {
      return limiters.get(EVERYTHING_ELSE);
    }
    if (!(handler instanceof HandlerMethod method)) {
      return null;
    }
    String operation = method.getMethod().getName();
    String own = OWN_LIMIT.get(operation);
    if (own != null) {
      return limiters.get(own);
    }
    return isApi(req) || LIMITED_OUTSIDE_THE_API.contains(operation)
        ? limiters.get(EVERYTHING_ELSE)
        : null;
  }

  private static boolean isApi(HttpServletRequest req) {
    return req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof String pattern
        && pattern.startsWith("/api/");
  }
}
