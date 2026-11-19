package app.hermes.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

  @Test
  fun newerPatchIsNewer() {
    assertTrue(Versions.isNewer("2.5.8", "2.5.7"))
  }

  @Test
  fun olderIsNotNewer() {
    assertFalse(Versions.isNewer("2.5.7", "2.5.8"))
  }

  @Test
  fun equalIsNotNewer() {
    assertFalse(Versions.isNewer("0.1.26", "0.1.26"))
  }

  @Test
  fun missingSegmentsCompareAsZero() {
    assertTrue(Versions.isNewer("1.0.1", "1.0"))
    assertFalse(Versions.isNewer("1.0", "1.0.1"))
  }

  @Test
  fun unparseableIsNotNewer() {
    assertFalse(Versions.isNewer("nightly", "1.0.0"))
    assertFalse(Versions.isNewer("1.0.0", "unknown"))
    assertFalse(Versions.isNewer("", ""))
  }

  @Test
  fun numericNotLexicographic() {
    assertTrue(Versions.isNewer("0.1.10", "0.1.9"))
    assertTrue(Versions.isNewer("10.0.0", "9.9.9"))
  }
}
