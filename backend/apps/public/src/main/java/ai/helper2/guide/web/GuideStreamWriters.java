package ai.helper2.guide.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class GuideStreamWriters implements WebMvcConfigurer {

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setTaskExecutor(writers());
  }

  static SimpleAsyncTaskExecutor writers() {
    SimpleAsyncTaskExecutor writers = new SimpleAsyncTaskExecutor("guide-stream-");
    writers.setVirtualThreads(true);
    writers.setTaskDecorator(new ContextPropagatingTaskDecorator());
    return writers;
  }
}
