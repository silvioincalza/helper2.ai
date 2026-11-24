package ai.searchavo.identity;

import java.util.Optional;
import java.util.UUID;

public interface CurrentUser {

  Optional<UUID> id();

  Optional<String> displayName();
}
