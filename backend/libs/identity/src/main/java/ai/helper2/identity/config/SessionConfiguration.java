package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import ai.helper2.platform.resilience.Resilience;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.http.MediaType;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Sign-in sessions are kept in Redis (spring.session.data.redis), so a signed-in admin is
 * recognised by every replica. Only sign-in creates one (each app's security configuration):
 * anonymous use never reads or writes a session.
 *
 * <p>While Redis is unavailable:
 *
 * <ul>
 *   <li>reading a session finds none, so a request with a session cookie is served as anonymous
 *       instead of failing (Spring MVC looks the session up on every such request);
 *   <li>saving one fails, so sign-in fails: the filter just outside Spring Session's sends the
 *       sign-in flow back to the app's sign-in page ({@link SignInPages}) with {@code
 *       ?signin=failed}, and anything else gets a 503, instead of a server error that fails again
 *       on the error page.
 * </ul>
 *
 * <p>Sessions are stored as JSON (ADR 0070), never with Java serialization: whoever can write a
 * session key could otherwise make the app run code while it reads the session. Only the types of
 * Spring Security's Jackson modules (the signed-in user, its roles, the sign-in request) are read
 * back.
 */
@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

  /** The serializer Spring Session uses for every session attribute (its bean name). */
  @Bean
  static RedisSerializer<Object> springSessionDefaultRedisSerializer() {
    ClassLoader loader = SessionConfiguration.class.getClassLoader();
    JsonMapper json =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(loader, attributeValues()))
            .build();
    return new GenericJacksonJsonRedisSerializer(json);
  }

  /**
   * The plain values a provider's user attributes hold (GitHub: numeric id, flags, nested objects
   * and lists), on top of Spring Security's own types. Exactly these classes, never their
   * subclasses: a subclass of a map or a list elsewhere on the classpath could run code when read.
   */
  private static final Set<Class<?>> ATTRIBUTE_VALUES =
      Set.of(
          String.class,
          Long.class,
          Integer.class,
          Double.class,
          Boolean.class,
          ArrayList.class,
          LinkedHashMap.class,
          HashMap.class);

  private static BasicPolymorphicTypeValidator.Builder attributeValues() {
    return BasicPolymorphicTypeValidator.builder()
        .allowIfSubType((context, type) -> ATTRIBUTE_VALUES.contains(type));
  }

  @Bean
  static BeanPostProcessor failSoftSessionReads(ObjectProvider<Resilience> resilience) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RedisSessionRepository redis) {
          return new FailSoftSessionRepository<>(redis, resilience.getObject());
        }
        return bean;
      }
    };
  }

  @Bean
  FilterRegistrationBean<SessionStoreFailureFilter> sessionStoreFailureFilter(SignInPages pages) {
    String failed = pages.failed();
    FilterRegistrationBean<SessionStoreFailureFilter> registration =
        new FilterRegistrationBean<>(new SessionStoreFailureFilter(req -> failed));
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
    return registration;
  }

  /**
   * Reads run under the {@code redis} policy (ADR 0033) and answer "no session" while the store
   * fails or its circuit is open; writes still fail.
   */
  static final class FailSoftSessionRepository<S extends Session> implements SessionRepository<S> {

    private static final Logger log = LoggerFactory.getLogger(FailSoftSessionRepository.class);

    private final SessionRepository<S> delegate;
    private final Resilience resilience;

    FailSoftSessionRepository(SessionRepository<S> delegate, Resilience resilience) {
      this.delegate = delegate;
      this.resilience = resilience;
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
      try {
        return resilience.call("redis", () -> delegate.findById(id));
      } catch (RuntimeException e) {
        if (unreadable(e)) {
          // Not an outage: the stored session has a type the JSON reader does not allow. The user
          // is signed out; say so, or every sign-in would end like this without a trace.
          log.warn("Session could not be read, served as anonymous: {}", e.toString());
        }
        return null;
      }
    }

    @Override
    public void deleteById(String id) {
      delegate.deleteById(id);
    }

    private static boolean unreadable(Throwable e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof SerializationException) {
          return true;
        }
      }
      return false;
    }
  }

  static final class SessionStoreFailureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreFailureFilter.class);

    /** Where a failed sign-in returns (the app's {@link SignInPages}). */
    private final java.util.function.Function<HttpServletRequest, String> signInFailed;

    SessionStoreFailureFilter(
        java.util.function.Function<HttpServletRequest, String> signInFailed) {
      this.signInFailed = signInFailed;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      try {
        chain.doFilter(req, res);
      } catch (ServletException | IOException | RuntimeException e) {
        Throwable failure = redisFailure(e);
        if (failure == null || res.isCommitted()) {
          throw e;
        }
        log.warn(
            "Session store unavailable for {}: {}",
            req.getRequestURI(),
            failure.getClass().getSimpleName());
        res.resetBuffer();
        String path = req.getRequestURI();
        if (path.startsWith("/oauth2/") || path.startsWith("/login/")) {
          res.sendRedirect(signInFailed.apply(req));
        } else {
          res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
          res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
          res.getWriter()
              .write(
                  Problems.json(
                      ProblemType.UNAVAILABLE,
                      "Sign-in is unavailable right now. Try again in a moment."));
        }
      }
    }

    /**
     * The Redis failure that ended the request (a timeout is Lettuce's), or null when it was
     * something else.
     */
    private static @Nullable Throwable redisFailure(Throwable e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof RedisConnectionFailureException
            || t instanceof RedisSystemException
            || t.getClass().getName().startsWith("io.lettuce.")) {
          return t;
        }
      }
      return null;
    }
  }
}
