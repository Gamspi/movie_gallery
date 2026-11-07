package dev.gamspi.videogallery.ui.components

import dev.gamspi.videogallery.settings.CatalogSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorViewTest {
    @Test fun keyErrors_identifySelectedProvider() {
        assertEquals("Нужен ключ PoiskKino", catalogErrorMessage(ErrorKind.MissingKey, CatalogSource.PoiskKino))
        assertEquals("Ключ PoiskKino неверный", catalogErrorMessage(ErrorKind.BadKey, CatalogSource.PoiskKino))
        assertEquals("Нужен ключ Кинопоиска", catalogErrorMessage(ErrorKind.MissingKey, CatalogSource.Kinopoisk))
        assertEquals("Ключ Кинопоиска неверный", catalogErrorMessage(ErrorKind.BadKey, CatalogSource.Kinopoisk))
        assertEquals("Нужен ключ TMDB", catalogErrorMessage(ErrorKind.MissingKey, CatalogSource.Tmdb))
        assertEquals("Ключ TMDB неверный", catalogErrorMessage(ErrorKind.BadKey, CatalogSource.Tmdb))
    }

    @Test fun settingsTarget_byErrorKind() {
        assertEquals(true, ErrorKind.MissingKey.opensKeySettings())
        assertEquals(true, ErrorKind.BadKey.opensKeySettings())
        assertEquals(true, ErrorKind.QuotaExceeded.opensKeySettings())
        assertEquals(false, ErrorKind.Network.opensKeySettings())
        assertEquals(false, ErrorKind.Other.opensKeySettings())
    }
}
