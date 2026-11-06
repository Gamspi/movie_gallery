package dev.gamspi.videogallery.settings

/** Read-through fallback: blank stored values resolve to build-time defaults; defaults are never written. */
class DefaultingSecretStore(
    private val delegate: SecretStore,
    private val defaults: Map<String, String>,
) : SecretStore {
    override fun get(key: String): String? =
        delegate.get(key)?.takeIf { it.isNotBlank() } ?: defaults[key]

    override fun put(key: String, value: String?) = delegate.put(key, value)
}
