package ai.helper2.sources.tavily;

import java.util.Locale;

public enum DiscoveryFamily {
  PROMPTS_AND_SKILLS,
  EVERYDAY_APPS,
  CREATIVE_AND_MEDIA,
  BUSINESS,
  DEVELOPERS,
  HOSTING_AND_INFRA,
  RUN_IT_YOURSELF;

  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
