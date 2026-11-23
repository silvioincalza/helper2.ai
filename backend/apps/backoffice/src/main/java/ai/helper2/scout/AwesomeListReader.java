package ai.helper2.scout;

import java.util.Collection;
import java.util.Optional;

public interface AwesomeListReader {

  Optional<Boolean> isAwesomeList(String repoFullName, Collection<String> topics);
}
