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

@Configuration(proxyBeanMethods = false)
class SessionConfiguration {
  @Bean
  static RedisSerializer<Object> springSessionDefaultRedisSerializer() {
    ClassLoader loader = SessionConfiguration.class.getClassLoader();
    JsonMapper json =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(loader, attributeValues()))
            .build();
    return new GenericJacksonJsonRedisSerializer(json);
  }

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
