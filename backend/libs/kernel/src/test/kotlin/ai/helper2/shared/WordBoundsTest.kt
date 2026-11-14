package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordBoundsTest {

  @Test
  fun `a quote must start and end on whole words`() {
    assertTrue(WordBounds.containsOnWords("runs models locally on your laptop", "models locally"))
    assertFalse(WordBounds.containsOnWords("set the locale first", "local"))
  }

  @Test
  fun `an underscore and a combining mark are part of the word`() {
    assertFalse(WordBounds.containsOnWords("set api_key to the server url", "key to the server"))
    assertFalse(WordBounds.containsOnWords("café latte", "cafe"))
    assertTrue(WordBounds.containsOnWords("हिन्दी में मॉडल", "में"))
  }

  @Test
  fun `a script written without spaces has a boundary between any two letters`() {
    assertTrue(WordBounds.containsOnWords("音声をテキストに変換するモデルです", "テキストに変換する"))
    assertTrue(WordBounds.containsOnWords("โมเดลแปลงเสียงเป็นข้อความ", "แปลงเสียง"))
  }
}
