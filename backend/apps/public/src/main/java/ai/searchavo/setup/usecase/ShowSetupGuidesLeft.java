package ai.searchavo.setup.usecase;

import ai.searchavo.setup.SetupGuidesLeft;
import ai.searchavo.setup.ToolGuides;
import org.jspecify.annotations.Nullable;

public class ShowSetupGuidesLeft {

  private final ToolGuides toolGuides;

  public ShowSetupGuidesLeft(ToolGuides toolGuides) {
    this.toolGuides = toolGuides;
  }

  public @Nullable SetupGuidesLeft of(String client) {
    return toolGuides.guidesLeft(client);
  }
}
