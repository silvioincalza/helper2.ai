package ai.helper2.guide.internal

import ai.helper2.catalog.Resources
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.dao.DataAccessResourceFailureException

class GuideQuoteRecordsTest {

  @Test
  fun `a database that cannot be read gives no stored records instead of failing the answer`() {
    val down =
      Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Resources::class.java)) { _, _, _ ->
        throw DataAccessResourceFailureException("database down")
      } as Resources
    val id = ResourceId.of(CanonicalUrl.of("https://github.com/acme/pdfchat")!!)

    assertEquals(emptyMap(), GuideConfiguration.quoteRecords(down).of(listOf(id)))
  }
}
