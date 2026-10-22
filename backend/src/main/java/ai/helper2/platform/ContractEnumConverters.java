package ai.helper2.platform;

import java.lang.reflect.Method;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Binds query parameters to generated contract enums by their wire VALUE (e.g. TriState "unknown"),
 * using the generated {@code fromValue}, instead of Spring's default binding by constant name.
 */
@Configuration(proxyBeanMethods = false)
class ContractEnumConverters implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new FromValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class FromValue implements ConverterFactory<String, Enum> {
        @Override
        public <T extends Enum> Converter<String, T> getConverter(Class<T> type) {
            Method fromValue = null;
            if (type.getPackageName().startsWith("ai.helper2.api.generated")) {
                try {
                    fromValue = type.getMethod("fromValue", String.class);
                } catch (NoSuchMethodException ignored) {
                    // fall back to name binding
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
