package dev.mimir.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtPriorityTest {
    @Test
    fun `priority order is folder over esde over sgdb over libretro`() {
        assertTrue(ArtPriority.canReplace(existing = "libretro", incoming = "sgdb"))
        assertTrue(ArtPriority.canReplace(existing = "sgdb", incoming = "esde"))
        assertTrue(ArtPriority.canReplace(existing = "esde", incoming = "folder"))
        assertFalse(ArtPriority.canReplace(existing = "folder", incoming = "libretro"))
        assertFalse(ArtPriority.canReplace(existing = "esde", incoming = "sgdb"))
    }

    @Test
    fun `same source can refresh itself and unknown sources lose to known`() {
        assertTrue(ArtPriority.canReplace(existing = "sgdb", incoming = "sgdb"))
        assertFalse(ArtPriority.canReplace(existing = "libretro", incoming = "mystery"))
        assertTrue(ArtPriority.canReplace(existing = "mystery", incoming = "libretro"))
    }
}
