package dev.gamspi.videogallery.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedTranslationTest {
    private val fake = FakeSubtitleTranslator()

    @Test fun translates_lines_and_caches_phrase() = runTest {
        val t = EmbeddedTranslation(fake, TranslateLang.EN)
        assertEquals("[Hello]\n[World]", t.translate("Hello\n \nWorld "))
        assertEquals("[Hello]\n[World]", t.translate("Hello\n \nWorld "))
        assertEquals(listOf("Hello", "World"), fake.texts)
        assertTrue(fake.calls.all { it.second == TranslateLang.EN && it.third == TranslateLang.RU })
    }

    @Test fun newer_cue_drops_older_result() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "One") gate.await() }
        val t = EmbeddedTranslation(fake, TranslateLang.EN)
        val one = async { t.translate("One") }
        runCurrent()
        assertEquals("[Two]", t.translate("Two"))
        gate.complete(Unit)
        assertNull(one.await())
        // The finished phrase is still cached for later use.
        t.translate("One")
        assertEquals(listOf("One", "Two"), fake.texts)
    }

    @Test fun invalidate_drops_running_result() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { gate.await() }
        val t = EmbeddedTranslation(fake, TranslateLang.RU)
        val one = async { t.translate("Привет") }
        runCurrent()
        t.invalidate()
        gate.complete(Unit)
        assertNull(one.await())
    }

    @Test fun lru_evicts_oldest_phrase() = runTest {
        val t = EmbeddedTranslation(fake, TranslateLang.EN, capacity = 2)
        t.translate("a")
        t.translate("b")
        t.translate("a")
        t.translate("c")
        t.translate("a")
        t.translate("b")
        assertEquals(listOf("a", "b", "c", "b"), fake.texts)
    }

    @Test fun series_of_failures_marks_failed() = runTest {
        fake.failWhen = { it.startsWith("bad") }
        val t = EmbeddedTranslation(fake, TranslateLang.EN)
        repeat(TRANSLATION_MAX_FAILURES - 1) { assertNull(t.translate("bad$it")) }
        assertEquals("[ok]", t.translate("ok"))
        assertFalse(t.failed)
        repeat(TRANSLATION_MAX_FAILURES) { assertNull(t.translate("bad$it")) }
        assertTrue(t.failed)
    }

    @Test fun default_capacity_is_500() {
        assertEquals(500, EMBEDDED_CACHE_SIZE)
    }
}
