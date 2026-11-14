package ai.helper2.setup.usecase;

import ai.helper2.setup.SetupGuidesLeft;
import ai.helper2.setup.ToolGuides;
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
