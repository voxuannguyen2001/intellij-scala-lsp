package org.jetbrains.scalalsP

import org.junit.Assert.*
import org.junit.Test

class ScalaTextDocumentServiceTest:

  import ScalaTextDocumentService.resolveMaxConcurrentRequests

  @Test def testDefaultIsQuarterOfCores(): Unit =
    assertEquals(4, resolveMaxConcurrentRequests(None, 16))
    assertEquals(3, resolveMaxConcurrentRequests(None, 12))
    assertEquals(2, resolveMaxConcurrentRequests(None, 8))

  @Test def testDefaultHasFloorOfTwo(): Unit =
    assertEquals(2, resolveMaxConcurrentRequests(None, 4))
    assertEquals(2, resolveMaxConcurrentRequests(None, 1))

  @Test def testEnvOverrideWins(): Unit =
    assertEquals(3, resolveMaxConcurrentRequests(Some("3"), 16))
    assertEquals(1, resolveMaxConcurrentRequests(Some("1"), 16))
    // An explicit override is trusted even above the core count.
    assertEquals(32, resolveMaxConcurrentRequests(Some("32"), 4))

  @Test def testEnvOverrideIsTrimmed(): Unit =
    assertEquals(6, resolveMaxConcurrentRequests(Some("  6  "), 16))

  @Test def testMissingOrInvalidEnvFallsBackToDefault(): Unit =
    val default = resolveMaxConcurrentRequests(None, 16)
    assertEquals(default, resolveMaxConcurrentRequests(Some(""), 16))
    assertEquals(default, resolveMaxConcurrentRequests(Some("   "), 16))
    assertEquals(default, resolveMaxConcurrentRequests(Some("abc"), 16))
    assertEquals(default, resolveMaxConcurrentRequests(Some("3.5"), 16))
    assertEquals(default, resolveMaxConcurrentRequests(Some("0"), 16))
    assertEquals(default, resolveMaxConcurrentRequests(Some("-5"), 16))
