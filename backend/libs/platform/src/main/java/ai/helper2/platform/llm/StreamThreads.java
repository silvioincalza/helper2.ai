package ai.helper2.platform.llm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

public final class StreamThreads {

  static final String DISPATCHER = "llm-dispatcher-";

  private StreamThreads() {}

  public static OpenAiHttpClientBuilderCustomizer virtualDispatcher() {
    ExecutorService dispatcher =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(DISPATCHER, 0).factory());
    return http -> http.dispatcherExecutorService(dispatcher);
  }
}
