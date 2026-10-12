package org.siloserver.silo.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryBlobStore(var contents: String? = null, var failWrites: Boolean = false) : BlobStore {
    var writes = 0
    override fun read(): String? = contents
    override fun write(contents: String): Boolean {
        if (failWrites) return false
        writes++; this.contents = contents; return true
    }
}

class PersistentPreferencesTest {
    @Test fun valuesSurviveReload() {
        val store = MemoryBlobStore()
        PersistentPreferences(store).edit()
            .putString("s", "hello").putLong("l", 42L).putBoolean("b", true).commit()
        val reloaded = PersistentPreferences(store)
        assertEquals("hello", reloaded.getString("s", null))
        assertEquals(42L, reloaded.getLong("l", 0L))
        assertTrue(reloaded.getBoolean("b", false))
        assertEquals(setOf("s", "l", "b"), reloaded.all.keys)
    }

    @Test fun defaultsWhenMissing() {
        val p = PersistentPreferences(MemoryBlobStore())
        assertNull(p.getString("x", null))
        assertEquals(7L, p.getLong("x", 7L))
        assertFalse(p.contains("x"))
    }

    @Test fun removeAndClear() {
        val store = MemoryBlobStore()
        val p = PersistentPreferences(store)
        p.edit().putString("a", "1").putString("b", "2").commit()
        p.edit().remove("a").commit()
        assertFalse(p.contains("a")); assertTrue(p.contains("b"))
        p.edit().clear().commit()
        assertTrue(PersistentPreferences(store).all.isEmpty())
    }

    @Test fun putNullStringRemovesKey() {
        val p = PersistentPreferences(MemoryBlobStore())
        p.edit().putString("a", "1").commit()
        p.edit().putString("a", null).commit()
        assertFalse(p.contains("a"))
    }

    @Test fun editsAreInvisibleUntilCommitted() {
        val p = PersistentPreferences(MemoryBlobStore())
        val e = p.edit().putString("a", "1")
        assertFalse(p.contains("a"))
        e.apply()
        assertTrue(p.contains("a"))
    }

    @Test fun commitReportsWriteFailureAndKeepsOldValue() {
        val store = MemoryBlobStore()
        val p = PersistentPreferences(store)
        p.edit().putString("a", "1").commit()
        store.failWrites = true
        assertFalse(p.edit().putString("a", "2").commit())
        assertEquals("1", p.getString("a", null))
    }

    @Test fun corruptBlobStartsEmpty() {
        val p = PersistentPreferences(MemoryBlobStore("{not json"))
        assertTrue(p.all.isEmpty())
    }
}
