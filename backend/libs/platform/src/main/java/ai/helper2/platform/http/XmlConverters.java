package ai.helper2.platform.http;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;

public final class XmlConverters {

  private XmlConverters() {}

  public static void remove(List<HttpMessageConverter<?>> converters) {
    converters.removeIf(XmlConverters::handlesXml);
  }

  public static boolean handlesXml(HttpMessageConverter<?> converter) {
    return converter.getSupportedMediaTypes().stream().anyMatch(XmlConverters::isXml);
  }

  private static boolean isXml(MediaType type) {
    String subtype = type.getSubtype();
    return !type.isWildcardType() && (subtype.equals("xml") || subtype.endsWith("+xml"));
  }
}
