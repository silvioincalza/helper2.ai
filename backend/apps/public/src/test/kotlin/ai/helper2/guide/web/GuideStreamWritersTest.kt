package ai.helper2.guide.web

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class GuideStreamWritersTest {

  @Test
  fun `stream events are written on virtual threads named for the guide stream`() {
    val thread = CompletableFuture<Thread>()

    GuideStreamWriters.writers().execute { thread.complete(Thread.currentThread()) }

    val writer = thread.get(5, TimeUnit.SECONDS)
    assertTrue(writer.isVirtual)
    assertTrue(writer.name.startsWith("guide-stream-"), writer.name)
  }
}
