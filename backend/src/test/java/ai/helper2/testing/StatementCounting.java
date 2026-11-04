package ai.helper2.testing;

import java.util.function.Supplier;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Counts the SQL statements a piece of code sends, so a test can pin how many queries a read takes
 * (an N+1 shows up as a failing count, not as a slow page). Import it in a Spring test and wrap the
 * call in {@link #count}.
 *
 * <p>The application's DataSource is wrapped by datasource-proxy. Counts are per thread: only
 * statements of the calling thread are counted, so other work in the same context does not change
 * the number.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StatementCounting {

  @Bean
  static BeanPostProcessor countingDataSource() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds
            && !(bean instanceof net.ttddyy.dsproxy.support.ProxyDataSource)) {
          return ProxyDataSourceBuilder.create(ds).name("counted").countQuery().build();
        }
        return bean;
      }
    };
  }

  public static <T> Counted<T> count(Supplier<T> work) {
    QueryCountHolder.clear();
    T result = work.get();
    long statements = QueryCountHolder.getGrandTotal().getTotal();
    QueryCountHolder.clear();
    return new Counted<>(result, statements);
  }

  /** A result and how many statements producing it took. */
  public record Counted<T>(T result, long statements) {}
}
