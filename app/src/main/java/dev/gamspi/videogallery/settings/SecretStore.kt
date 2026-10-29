package dev.gamspi.videogallery.settings

interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
