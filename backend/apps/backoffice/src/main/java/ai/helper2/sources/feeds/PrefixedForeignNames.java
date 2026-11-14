package ai.helper2.sources.feeds;

import java.util.Set;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

final class PrefixedForeignNames extends StreamReaderDelegate {

  private static final Set<String> FEED_NAMESPACES = Set.of("", "http://www.w3.org/2005/Atom");

  PrefixedForeignNames(XMLStreamReader reader) {
    super(reader);
  }

  @Override
  public String getLocalName() {
    String name = super.getLocalName();
    String namespace = getNamespaceURI();
    if (namespace == null || FEED_NAMESPACES.contains(namespace)) {
      return name;
    }
    String prefix = getPrefix();
    return (prefix == null || prefix.isEmpty() ? namespace : prefix) + ":" + name;
  }
}
