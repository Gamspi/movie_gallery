package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DualTextTest {
    @Test fun slots_map_to_first_and_second_text_renderer() {
        assertEquals(mapOf(2 to (4 to 0), 5 to (6 to 1)), textSlotDefinitions(listOf(2, 5), 4 to 0, 6 to 1))
    }

    @Test fun null_slot_leaves_renderer_empty() {
        assertEquals(mapOf(5 to (6 to 1)), textSlotDefinitions(listOf(2, 5), null, 6 to 1))
        assertEquals(mapOf(2 to (4 to 0)), textSlotDefinitions(listOf(2, 5), 4 to 0, null))
        assertEquals(emptyMap<Int, Pair<Int, Int>>(), textSlotDefinitions(listOf(2, 5), null, null))
    }

    @Test fun same_group_is_not_assigned_twice() {
        assertEquals(mapOf(2 to (4 to 0)), textSlotDefinitions(listOf(2, 5), 4 to 0, 4 to 0))
        assertEquals(mapOf(2 to (4 to 0)), textSlotDefinitions(listOf(2, 5), 4 to 0, 4 to 1))
    }

    @Test fun missing_renderer_does_not_steal_other_layer() {
        assertEquals(emptyMap<Int, Pair<Int, Int>>(), textSlotDefinitions(listOf(2), null, 6 to 1))
        assertEquals(emptyMap<Int, Pair<Int, Int>>(), textSlotDefinitions(emptyList(), 4 to 0, 6 to 1))
    }
}
