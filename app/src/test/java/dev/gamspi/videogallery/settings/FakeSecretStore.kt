package dev.gamspi.videogallery.settings

class FakeSecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val map = initial.toMutableMap()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
