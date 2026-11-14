package ai.helper2.api;

import java.lang.reflect.Method;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class ContractEnumConverters implements WebMvcConfigurer {

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverterFactory(new FromValue());
  }

  private static final List<String> CONTRACT_PACKAGES =
      List.of("ai.helper2.api.common", "ai.helper2.api.generated");

  static boolean isContractType(Class<?> type) {
    return CONTRACT_PACKAGES.stream().anyMatch(type.getPackageName()::startsWith);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static final class FromValue implements ConverterFactory<String, Enum> {
    @Override
    public <T extends Enum> Converter<String, T> getConverter(Class<T> type) {
      Method fromValue = null;
      if (isContractType(type)) {
        try {
          fromValue = type.getMethod("fromValue", String.class);
        } catch (NoSuchMethodException ignored) {
        }
      }
      Method m = fromValue;
      return source -> {
        if (source.isEmpty()) {
          return null;
        }
        if (m != null) {
          try {
            return (T) m.invoke(null, source.trim());
          } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("invalid value", e);
          }
        }
        return (T) Enum.valueOf(type, source.trim());
      };
    }
  }
}
