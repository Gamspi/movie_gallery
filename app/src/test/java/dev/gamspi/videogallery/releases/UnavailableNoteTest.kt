package dev.gamspi.videogallery.releases

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Test

class UnavailableNoteTest {
    @Test
    fun rutrackerReasons() {
        val id = ProviderId.Rutracker
        assertEquals("rutracker просит капчу — войди один раз в браузере", unavailableNote(id, UnavailableReason.Captcha))
        assertEquals("rutracker: неверный логин или пароль", unavailableNote(id, UnavailableReason.BadCredentials))
        assertEquals("rutracker: укажи логин и пароль в настройках", unavailableNote(id, UnavailableReason.NoCredentials))
        assertEquals("rutracker недоступен", unavailableNote(id, UnavailableReason.Unavailable))
    }

    @Test
    fun otherProviders_keepGenericNote() {
        assertEquals("rutor недоступен", unavailableNote(ProviderId.Rutor, null))
        assertEquals("apibay недоступен", unavailableNote(ProviderId.Apibay, UnavailableReason.Unavailable))
    }
}
