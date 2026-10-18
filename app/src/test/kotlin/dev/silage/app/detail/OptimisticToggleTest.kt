package dev.silage.app.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimisticToggleTest {
    @Test fun flipsAndKeepsTheNewValueWhenTheServerAccepts() = runTest {
        val writes = mutableListOf<Boolean>()
        val t = OptimisticToggle(false) { writes += it; true }
        assertTrue(t.toggle())
        assertEquals(true, t.value)
        assertEquals(listOf(true), writes)
        assertFalse(t.failed)
    }

    @Test fun flipsBackAndReportsWhenTheServerRefusesOrThrows() = runTest {
        val refused = OptimisticToggle(true) { false }
        assertFalse(refused.toggle())
        assertEquals(true, refused.value)
        assertTrue(refused.failed)

        val throws = OptimisticToggle(false) { error("offline") }
        assertFalse(throws.toggle())
        assertEquals(false, throws.value)
        assertTrue(throws.failed)
    }

    @Test fun showsTheNewValueWhileTheWriteIsInFlightAndIgnoresRepeatPresses() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var calls = 0
        val t = OptimisticToggle(false) { calls++; gate.await() }
        val first = async { t.toggle() }
        testScheduler.runCurrent()
        assertEquals(true, t.value, "flips immediately")
        assertTrue(t.busy)
        assertFalse(t.toggle(), "second press while busy is ignored")
        t.load(false)
        assertEquals(true, t.value, "a stale server read can't undo the press")
        gate.complete(true)
        assertTrue(first.await())
        assertEquals(1, calls)
    }

    @Test fun anUnknownValueCannotBeToggled() = runTest {
        val t = OptimisticToggle(null) { true }
        assertFalse(t.toggle())
        assertNull(t.value)
        t.load(true)
        assertEquals(true, t.value)
    }
}
