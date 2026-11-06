package dev.gamspi.videogallery.settings

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import dev.gamspi.videogallery.net.ProxyConfig
import dev.gamspi.videogallery.torrents.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun torrentStorage_defaultsDeleteAndTenGiB() {
        val prefs = SettingsRepository(FakeSecretStore()).torrentStoragePrefs.value
        assertEquals(TorrentStoragePrefs(false, 10), prefs)
        assertEquals(10L * 1024 * 1024 * 1024, prefs.limitBytes)
    }

    @Test
    fun torrentStorage_persistsAndNormalizesLimit() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTorrentStoragePrefs(TorrentStoragePrefs(true, 150))
        assertEquals(TorrentStoragePrefs(true, 100), repo.torrentStoragePrefs.value)
        assertEquals(repo.torrentStoragePrefs.value, SettingsRepository(store).torrentStoragePrefs.value)
        repo.setTorrentStoragePrefs(TorrentStoragePrefs(false, -4))
        assertEquals(TorrentStoragePrefs(false, 1), SettingsRepository(store).torrentStoragePrefs.value)
    }

    @Test
    fun torrentStorage_corruptValuesFallBackWithoutEnablingRetention() {
        val repo = SettingsRepository(FakeSecretStore(mapOf(
            "torrent_keep_after_playback" to "unexpected",
            "torrent_limit_gib" to "not-a-number",
        )))
        assertEquals(TorrentStoragePrefs(), repo.torrentStoragePrefs.value)
        assertEquals(1, SettingsRepository(FakeSecretStore(mapOf("torrent_limit_gib" to "-3"))).torrentStoragePrefs.value.limitGiB)
        assertEquals(100, SettingsRepository(FakeSecretStore(mapOf("torrent_limit_gib" to "1000"))).torrentStoragePrefs.value.limitGiB)
    }

    @Test
    fun setKey_trimsAndStores() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTmdbApiKey("  abc ")
        assertEquals("abc", repo.tmdbApiKey.value)
        assertEquals("abc", store.get("tmdb_api_key"))
    }

    @Test
    fun subtitlePrefs_defaults() {
        val p = SettingsRepository(FakeSecretStore()).subtitlePrefs.value
        assertEquals("en", p.languageA)
        assertEquals("ru", p.languageB)
        assertEquals(SubColor.White, p.styleA.color)
        assertEquals(SubColor.Yellow, p.styleB.color)
    }

    @Test
    fun subtitlePrefs_persist_roundtrip() {
        val store = FakeSecretStore()
        val prefs = SubtitlePrefs(
            languageA = "de",
            languageB = "ja",
            styleA = LayerStyle(130, SubColor.Cyan, -1500),
            styleB = LayerStyle(70, SubColor.White, 2000),
        )
        SettingsRepository(store).setSubtitlePrefs(prefs)
        assertEquals(prefs, SettingsRepository(store).subtitlePrefs.value)
        assertEquals("130", store.get("sub_size_a"))
    }

    @Test
    fun subtitleScale_defaultsTo100() {
        val p = SettingsRepository(FakeSecretStore()).subtitlePrefs.value
        assertEquals(100, p.styleA.scalePercent)
        assertEquals(100, p.styleB.scalePercent)
    }

    @Test
    fun subtitleScale_migratesLegacySizeNames() {
        val store = FakeSecretStore(mapOf("sub_size_a" to "Small", "sub_size_b" to "Large"))
        val repo = SettingsRepository(store)
        assertEquals(80, repo.subtitlePrefs.value.styleA.scalePercent)
        assertEquals(130, repo.subtitlePrefs.value.styleB.scalePercent)
        assertEquals(100, SettingsRepository(FakeSecretStore(mapOf("sub_size_a" to "Medium")))
            .subtitlePrefs.value.styleA.scalePercent)
        // The next save rewrites the legacy names as percents.
        repo.setSubtitlePrefs(repo.subtitlePrefs.value)
        assertEquals("80", store.get("sub_size_a"))
        assertEquals("130", store.get("sub_size_b"))
    }

    @Test
    fun subtitleScale_corruptOrOutOfRangeValues() {
        fun scaleOf(raw: String) =
            SettingsRepository(FakeSecretStore(mapOf("sub_size_a" to raw))).subtitlePrefs.value.styleA.scalePercent
        assertEquals(100, scaleOf("Huge"))
        assertEquals(100, scaleOf(""))
        assertEquals(50, scaleOf("10"))
        assertEquals(200, scaleOf("900"))
        assertEquals(120, scaleOf("117"))
    }

    @Test
    fun subtitleScale_saveNormalizes() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setSubtitlePrefs(SubtitlePrefs(styleA = LayerStyle(scalePercent = 333)))
        assertEquals(200, repo.subtitlePrefs.value.styleA.scalePercent)
        assertEquals("200", store.get("sub_size_a"))
    }

    @Test
    fun openSubtitles_blank_is_null() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setOpenSubtitles("key", "user", "pw")
        assertEquals("key", repo.openSubtitlesKey)
        assertEquals("pw", SettingsRepository(store).openSubtitlesPassword)
        repo.setOpenSubtitles("", "", "")
        assertNull(repo.openSubtitlesKey)
        assertNull(repo.openSubtitlesUser)
        assertNull(repo.openSubtitlesPassword)
        assertNull(store.get("opensubtitles_key"))
    }

    @Test
    fun setKey_blankClears() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTmdbApiKey("abc")
        repo.setTmdbApiKey("   ")
        assertNull(repo.tmdbApiKey.value)
        assertNull(store.get("tmdb_api_key"))
    }

    @Test
    fun source_defaultsToPoiskKino() {
        val repo = SettingsRepository(FakeSecretStore())
        assertEquals(CatalogSource.PoiskKino, repo.catalogSource.value)
    }

    @Test
    fun storedLegacySources_arePreservedWithoutInventingMigration() {
        for (source in listOf(CatalogSource.Kinopoisk, CatalogSource.Tmdb)) {
            val store = FakeSecretStore(mapOf("catalog_source" to source.name,
                "kinopoisk_api_key" to "kp", "tmdb_api_key" to "tm"))
            val repo = SettingsRepository(store)
            assertEquals(source, repo.catalogSource.value)
            assertEquals(if (source == CatalogSource.Kinopoisk) "kp" else "tm", repo.activeCatalog.value.apiKey)
            assertEquals(source.name, store.get("catalog_source"))
        }
    }

    @Test
    fun poiskkinoKey_trimsAndBlankClears_persistsRoundtrip() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setPoiskkinoApiKey("  pk ")
        assertEquals("pk", repo.poiskkinoApiKey.value)
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals("pk", SettingsRepository(store).poiskkinoApiKey.value)
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "pk"), repo.activeCatalog.value)
        repo.setPoiskkinoApiKey("  ")
        assertNull(repo.poiskkinoApiKey.value)
        assertNull(store.get("poiskkino_api_key"))
        assertNull(repo.activeCatalog.value.apiKey)
    }

    @Test
    fun setSource_persists() {
        val store = FakeSecretStore()
        SettingsRepository(store).setCatalogSource(CatalogSource.Tmdb)
        assertEquals(CatalogSource.Tmdb, SettingsRepository(store).catalogSource.value)
    }

    @Test
    fun kinopoiskKey_trimsAndBlankClears() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setKinopoiskApiKey("  kp ")
        assertEquals("kp", repo.kinopoiskApiKey.value)
        assertEquals("kp", store.get("kinopoisk_api_key"))
        repo.setKinopoiskApiKey("  ")
        assertNull(repo.kinopoiskApiKey.value)
        assertNull(store.get("kinopoisk_api_key"))
    }

    @Test
    fun activeCatalog_followsSourceAndKey() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "A", "kinopoisk_api_key" to "B")))
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, null), repo.activeCatalog.value)
        repo.setCatalogSource(CatalogSource.Kinopoisk)
        assertEquals(ActiveCatalog(CatalogSource.Kinopoisk, "B"), repo.activeCatalog.value)
        repo.setCatalogSource(CatalogSource.Tmdb)
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "A"), repo.activeCatalog.value)
        repo.setTmdbApiKey("C")
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "C"), repo.activeCatalog.value)
    }

    @Test
    fun unknownOrBlankStoredSource_fallsBackToPoiskKino() {
        for (stored in listOf("Bogus", "", "   ")) {
            val repo = SettingsRepository(FakeSecretStore(mapOf("catalog_source" to stored)))
            assertEquals(CatalogSource.PoiskKino, repo.catalogSource.value)
        }
    }

    @Test
    fun inactivePoiskkinoKey_doesNotChangeActiveCatalog() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("catalog_source" to "Tmdb", "tmdb_api_key" to "tm")))
        repo.setPoiskkinoApiKey("pk")
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "tm"), repo.activeCatalog.value)
        repo.setCatalogSource(CatalogSource.PoiskKino)
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "pk"), repo.activeCatalog.value)
    }

    @Test
    fun initialValue_readFromStore() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "xyz")))
        assertEquals("xyz", repo.tmdbApiKey.value)
    }

    @Test
    fun update_emitsActiveCatalogOnce() = runBlocking {
        val repo = SettingsRepository(FakeSecretStore())
        repo.activeCatalog.test {
            assertEquals(ActiveCatalog(CatalogSource.PoiskKino, null), awaitItem())
            repo.update(CatalogSource.Tmdb, "pk", "kp", "tm")
            assertEquals(ActiveCatalog(CatalogSource.Tmdb, "tm"), awaitItem())
            expectNoEvents()
        }
        assertEquals("kp", repo.kinopoiskApiKey.value)
        assertEquals("pk", repo.poiskkinoApiKey.value)
    }

    @Test
    fun updatePoiskkino_persistsThreeKeys_andEmitsOnlyFinalActiveCatalog() = runBlocking {
        val store = FakeSecretStore(mapOf("catalog_source" to "Kinopoisk", "kinopoisk_api_key" to "old"))
        val repo = SettingsRepository(store)
        repo.activeCatalog.test {
            assertEquals(ActiveCatalog(CatalogSource.Kinopoisk, "old"), awaitItem())
            repo.update(CatalogSource.PoiskKino, " pk ", " kp ", " tm ")
            assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "pk"), awaitItem())
            expectNoEvents()
        }
        val reopened = SettingsRepository(store)
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "pk"), reopened.activeCatalog.value)
        assertEquals("kp", reopened.kinopoiskApiKey.value)
        assertEquals("tm", reopened.tmdbApiKey.value)
    }

    @Test
    fun trackerDefaults() {
        val repo = SettingsRepository(FakeSecretStore())
        assertEquals(TrackerConfig(true, "https://rutor.info/"), repo.tracker(ProviderId.Rutor).value)
        assertEquals(TrackerConfig(true, "https://apibay.org/"), repo.tracker(ProviderId.Apibay).value)
        assertEquals(TrackerConfig(false, "https://yts.lt/"), repo.tracker(ProviderId.Yts).value)
    }

    @Test
    fun rutracker_disabledByDefaultWithoutLogin() {
        val repo = SettingsRepository(FakeSecretStore())
        assertEquals(TrackerConfig(false, "https://rutracker.org/"), repo.tracker(ProviderId.Rutracker).value)
        assertNull(repo.rutrackerUser)
    }

    @Test
    fun rutracker_enabledByDefaultWhenLoginFromEnvDefaults() {
        val store = DefaultingSecretStore(
            FakeSecretStore(),
            mapOf(SettingsRepository.KEY_RUTRACKER_USER to "fake_user"),
        )
        val repo = SettingsRepository(store)
        assertEquals(TrackerConfig(true, "https://rutracker.org/"), repo.tracker(ProviderId.Rutracker).value)
        assertEquals("fake_user", repo.rutrackerUser)
    }

    @Test
    fun rutracker_storedToggleWinsOverLoginDefault() {
        val store = FakeSecretStore(
            mapOf(SettingsRepository.KEY_RUTRACKER_USER to "fake_user", "tracker_Rutracker_enabled" to "false"),
        )
        assertFalse(SettingsRepository(store).tracker(ProviderId.Rutracker).value.enabled)
    }

    @Test
    fun setRutracker_trimsUser_blankFallsBackToDefault() {
        val inner = FakeSecretStore()
        val store = DefaultingSecretStore(inner, mapOf(SettingsRepository.KEY_RUTRACKER_USER to "env_user"))
        val repo = SettingsRepository(store)
        repo.setRutracker("  tv_user ", "fake pass ")
        assertEquals("tv_user", inner.get(SettingsRepository.KEY_RUTRACKER_USER))
        assertEquals("fake pass ", inner.get(SettingsRepository.KEY_RUTRACKER_PASSWORD))
        assertEquals("tv_user", repo.rutrackerUser)
        assertEquals("fake pass ", repo.rutrackerPassword)
        repo.setRutracker(" ", "")
        assertNull(inner.get(SettingsRepository.KEY_RUTRACKER_USER))
        assertEquals("env_user", repo.rutrackerUser)
        assertNull(repo.rutrackerPassword)
    }

    @Test
    fun setTrackers_normalizesUrl_blankFallsBack() {
        val repo = SettingsRepository(FakeSecretStore())
        repo.setTrackers(
            mapOf(
                ProviderId.Rutor to TrackerConfig(true, "  rutor.info  "),
                ProviderId.Apibay to TrackerConfig(false, "   "),
                ProviderId.Yts to TrackerConfig(true, "http://yts.example/x"),
            ),
        )
        assertEquals("https://rutor.info/", repo.tracker(ProviderId.Rutor).value.baseUrl)
        assertEquals(TrackerConfig(false, "https://apibay.org/"), repo.tracker(ProviderId.Apibay).value)
        assertEquals("http://yts.example/x/", repo.tracker(ProviderId.Yts).value.baseUrl)
    }

    @Test
    fun setTrackers_persists() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTrackers(
            mapOf(
                ProviderId.Yts to TrackerConfig(true, "https://y.io/"),
                ProviderId.Rutor to TrackerConfig(false, "https://r.io/"),
            ),
        )
        assertEquals("true", store.get("tracker_Yts_enabled"))
        assertEquals("https://y.io/", store.get("tracker_Yts_url"))
        assertEquals("false", store.get("tracker_Rutor_enabled"))
        assertEquals(TrackerConfig(true, "https://y.io/"), repo.tracker(ProviderId.Yts).value)
        val reread = SettingsRepository(store)
        assertEquals(TrackerConfig(true, "https://y.io/"), reread.tracker(ProviderId.Yts).value)
        assertEquals(TrackerConfig(false, "https://r.io/"), reread.tracker(ProviderId.Rutor).value)
    }

    @Test
    fun testUrl_trimsAndBlankClears() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        assertNull(repo.testUrl.value)
        repo.setTestUrl("  http://x/a.mp4 ")
        assertEquals("http://x/a.mp4", repo.testUrl.value)
        assertEquals("http://x/a.mp4", store.get("test_url"))
        repo.setTestUrl("  ")
        assertNull(repo.testUrl.value)
        assertNull(store.get("test_url"))
    }

    @Test
    fun proxy_disabledByDefault_andNeedsUrl() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("proxy_url" to "https://w.dev", "proxy_key" to "k")))
        assertFalse(repo.proxyEnabled.value)
        assertNull(repo.proxyConfig())
        repo.setProxyEnabled(true)
        assertEquals(ProxyConfig("https://w.dev", "k"), repo.proxyConfig())
        repo.setProxyUrl("   ")
        assertNull(repo.proxyConfig())
    }

    @Test
    fun proxy_persistsAndNormalizes() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setProxyEnabled(true)
        repo.setProxyUrl("  w.workers.dev/ ")
        repo.setProxyKey(" k ")
        assertEquals(ProxyConfig("https://w.workers.dev", "k"), repo.proxyConfig())
        assertEquals("true", store.get("proxy_enabled"))
        val reopened = SettingsRepository(store)
        assertEquals(true, reopened.proxyEnabled.value)
        assertEquals(ProxyConfig("https://w.workers.dev", "k"), reopened.proxyConfig())
        repo.setProxyKey("")
        assertEquals(ProxyConfig("https://w.workers.dev", null), repo.proxyConfig())
        repo.setProxyEnabled(false)
        assertNull(repo.proxyConfig())
    }

    @Test
    fun proxy_invalidUrlGivesNoConfig() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("proxy_enabled" to "true")))
        repo.setProxyUrl("ftp://w.dev")
        assertNull(repo.proxyConfig())
        repo.setProxyUrl("http://")
        assertNull(repo.proxyConfig())
    }

    @Test
    fun clearedFields_showBuildDefaults() {
        val defaults = mapOf(
            "tmdb_api_key" to "env-tm", "kinopoisk_api_key" to "env-kp", "poiskkino_api_key" to "env-pk",
            "opensubtitles_key" to "env-os", "proxy_url" to "https://env.dev", "proxy_key" to "env-key",
        )
        val repo = SettingsRepository(DefaultingSecretStore(FakeSecretStore(), defaults))
        repo.setTmdbApiKey("mine")
        assertEquals("mine", repo.tmdbApiKey.value)
        repo.setTmdbApiKey("")
        assertEquals("env-tm", repo.tmdbApiKey.value)
        repo.setKinopoiskApiKey(" ")
        assertEquals("env-kp", repo.kinopoiskApiKey.value)
        repo.update(CatalogSource.PoiskKino, "", "", "")
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "env-pk"), repo.activeCatalog.value)
        repo.setOpenSubtitles("", "", "")
        assertEquals("env-os", repo.openSubtitlesKey)
        repo.setProxyEnabled(true)
        repo.setProxyUrl("https://mine.dev")
        repo.setProxyKey("")
        assertEquals(ProxyConfig("https://mine.dev", "env-key"), repo.proxyConfig())
        repo.setProxyUrl("")
        assertEquals("https://env.dev", repo.proxyUrl.value)
        assertEquals(ProxyConfig("https://env.dev", "env-key"), repo.proxyConfig())
    }
}
