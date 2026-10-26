package ai.helper2.identity;

import ai.helper2.platform.CircuitBreaker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.MediaType;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sign-in sessions are kept in Redis (spring.session.data.redis), so a signed-in admin is recognised by every
 * replica. Only sign-in creates one (SecurityConfiguration): anonymous use never reads or writes a session.
 *
 * <p>While Redis is unavailable:
 * <ul>
 *   <li>reading a session finds none, so a request with a session cookie is served as anonymous instead of
 *       failing (Spring MVC looks the session up on every such request);</li>
 *   <li>saving one fails, so sign-in fails: the filter just outside Spring Session's sends the sign-in flow back
 *       to the admin page with {@code ?signin=failed}, and anything else gets a 503, instead of a server error
 *       that fails again on the error page.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

    @Bean
    static BeanPostProcessor failSoftSessionReads(ObjectProvider<Clock> clock) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RedisSessionRepository redis) {
                    return new FailSoftSessionRepository<>(redis, new CircuitBreaker("session store", clock.getObject(), Duration.ofSeconds(30)));
                }
                return bean;
            }
        };
    }

    @Bean
    FilterRegistrationBean<SessionStoreFailureFilter> sessionStoreFailureFilter(@Value("${helper2.frontend-url:/}") String frontendUrl) {
        FilterRegistrationBean<SessionStoreFailureFilter> registration =
            new FilterRegistrationBean<>(new SessionStoreFailureFilter(SecurityConfiguration.adminPage(frontendUrl) + "?signin=failed"));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    /** Reads answer "no session" while the store fails, and skip it for a while after a failure; writes still fail. */
    static final class FailSoftSessionRepository<S extends Session> implements SessionRepository<S> {

        private final SessionRepository<S> delegate;
        private final CircuitBreaker breaker;

        FailSoftSessionRepository(SessionRepository<S> delegate, CircuitBreaker breaker) {
            this.delegate = delegate;
            this.breaker = breaker;
        }

        @Override
        public S createSession() {
            return delegate.createSession();
        }

        @Override
        public void save(S session) {
            delegate.save(session);
        }

        @Override
        public @Nullable S findById(String id) {
            if (breaker.isOpen()) {
                return null;
            }
            try {
                return delegate.findById(id);
            } catch (RuntimeException e) {
                breaker.trip(e);
                return null;
            }
        }

        @Override
        public void deleteById(String id) {
            delegate.deleteById(id);
        }
    }

    static final class SessionStoreFailureFilter extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(SessionStoreFailureFilter.class);

        private final String signInFailed;

        SessionStoreFailureFilter(String signInFailed) {
            this.signInFailed = signInFailed;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
            try {
                chain.doFilter(req, res);
            } catch (ServletException | IOException | RuntimeException e) {
                Throwable failure = redisFailure(e);
                if (failure == null || res.isCommitted()) {
                    throw e;
                }
                log.warn("Session store unavailable for {}: {}", req.getRequestURI(), failure.getClass().getSimpleName());
                res.resetBuffer();
                String path = req.getRequestURI();
                if (path.startsWith("/oauth2/") || path.startsWith("/login/")) {
                    res.sendRedirect(signInFailed);
                } else {
                    res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                        + "\"detail\":\"Sign-in is unavailable right now. Try again in a moment.\"}");
                }
            }
        }

        /** The Redis failure that ended the request (a timeout is Lettuce's), or null when it was something else. */
        private static @Nullable Throwable redisFailure(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof RedisConnectionFailureException || t instanceof RedisSystemException
                    || t.getClass().getName().startsWith("io.lettuce.")) {
                    return t;
                }
            }
            return null;
        }
    }
}
