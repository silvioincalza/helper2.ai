package ai.helper2.platform;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * duplicate slashes) that still reach the same controller. Counted in Redis, shared by all replicas;
 * in each replica's memory while Redis is down (RedisRateLimiter).
 */
@Configuration(proxyBeanMethods = false)
class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** Contract operationIds (generated interface method names) that are limited. */
    private final Map<String, RateLimiter> limiters;

    private final StringRedisTemplate redis;
    private final CircuitBreaker breaker;
    private final Clock clock;
    private final MeterRegistry meters;

    RateLimitInterceptor(Clock clock, ObjectProvider<StringRedisTemplate> redis, MeterRegistry meters,
                         @Value("${helper2.rate-limit.search-per-minute:60}") int searchPerMinute,
                         @Value("${helper2.rate-limit.report-per-hour:20}") int reportsPerHour,
                         @Value("${helper2.rate-limit.guide-per-minute:10}") int guidePerMinute,
                         @Value("${helper2.rate-limit.recent-per-minute:60}") int recentPerMinute) {
        this.redis = redis.getIfAvailable();
        this.breaker = new CircuitBreaker("rate limiter", clock, Duration.ofSeconds(30));
        this.clock = clock;
        this.meters = meters;
        // Each guide question costs three LLM calls: keep it tighter than search. The JSON and the streamed
        // endpoint share one limiter, so switching endpoint does not double a client's budget.
        RateLimiter guide = limiter("guide", guidePerMinute, Duration.ofMinutes(1));
        this.limiters = Map.of(
            "search", limiter("search", searchPerMinute, Duration.ofMinutes(1)),
            "reportResource", limiter("report", reportsPerHour, Duration.ofHours(1)),
            "askGuide", guide,
            "askGuideStream", guide,
            // The tool guide can call a model too.
            "askResourceGuide", guide,
            // Can fetch a README from GitHub (on the sources' token) when the server has no registry entry.
            "getResourceSetup", limiter("setup", searchPerMinute, Duration.ofMinutes(1)),
            // Home page "Today" list: a cheap index read, limited only so it cannot be looped against the database.
            "listRecent", limiter("recent", recentPerMinute, Duration.ofMinutes(1)));
    }

    private RateLimiter limiter(String name, int limit, Duration window) {
        RateLimiter inMemory = new InMemoryRateLimiter(limit, window, clock);
        return redis == null ? inMemory : new RedisRateLimiter(name, limit, window, redis, breaker, inMemory, meters);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        // An async handler (the streamed guide) is dispatched again when it completes: charge only the first dispatch.
        if (!(handler instanceof HandlerMethod hm) || req.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        RateLimiter limiter = limiters.get(hm.getMethod().getName());
        if (limiter == null) {
            return true;
        }
        // remoteAddr honours X-Forwarded-For only when H2_FORWARD_HEADERS_STRATEGY is set behind a trusted proxy.
        long wait = limiter.acquire(req.getRemoteAddr());
        if (wait == 0) {
            return true;
        }
        res.setStatus(429);
        res.setHeader("Retry-After", Long.toString(wait));
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
            + "\"detail\":\"Rate limit reached. Try again in " + wait + " seconds.\"}");
        return false;
    }
}
