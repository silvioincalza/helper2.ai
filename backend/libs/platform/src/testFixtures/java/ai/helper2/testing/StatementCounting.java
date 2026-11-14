package ai.helper2.testing;

import java.util.function.Supplier;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

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

  public record Counted<T>(T result, long statements) {}

  public static <T> Counted<T> count(Supplier<T> work) {
    QueryCountHolder.clear();
    T result = work.get();
    long statements = QueryCountHolder.getGrandTotal().getTotal();
    QueryCountHolder.clear();
    return new Counted<>(result, statements);
  }
}
