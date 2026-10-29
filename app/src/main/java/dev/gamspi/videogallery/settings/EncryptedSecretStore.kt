package dev.gamspi.videogallery.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences? = openOrNull(context)
    // fallback when keystore is unusable: key is lost on restart, but no crash
    private val memory = mutableMapOf<String, String>()

    override fun get(key: String): String? = prefs?.getString(key, null) ?: memory[key]

    override fun put(key: String, value: String?) {
        if (prefs == null) {
            if (value == null) memory.remove(key) else memory[key] = value
            return
        }
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val FILE = "secrets"

        fun create(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun openOrNull(context: Context): SharedPreferences? = try {
            create(context)
        } catch (e: Exception) {
            Log.w("EncryptedSecretStore", "Keystore/prefs corrupted, resetting", e)
            context.deleteSharedPreferences(FILE)
            try {
                create(context)
            } catch (e2: Exception) {
                Log.w("EncryptedSecretStore", "Retry failed, using in-memory store", e2)
                null
            }
        }
    }
}
