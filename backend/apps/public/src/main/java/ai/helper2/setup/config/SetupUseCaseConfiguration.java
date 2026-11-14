package ai.helper2.setup.config;

import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.usecase.GetResourceSetup;
import ai.helper2.setup.usecase.GetToolGuide;
import ai.helper2.setup.usecase.ShowSetupGuidesLeft;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SetupUseCaseConfiguration {

  @Bean
  GetResourceSetup getResourceSetup(GetResourceDetail details, SetupGuides guides) {
    return new GetResourceSetup(details, guides);
  }

  @Bean
  GetToolGuide getToolGuide(GetResourceDetail details, ToolGuides toolGuides) {
    return new GetToolGuide(details, toolGuides);
  }

  @Bean
  ShowSetupGuidesLeft showSetupGuidesLeft(ToolGuides toolGuides) {
    return new ShowSetupGuidesLeft(toolGuides);
  }
}
