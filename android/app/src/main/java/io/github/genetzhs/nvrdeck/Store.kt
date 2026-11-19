package io.github.genetzhs.nvrdeck

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Όλα τα δεδομένα (κάμερες με κωδικούς, PIN) αποθηκεύονται κρυπτογραφημένα
 * με κλειδί του Android Keystore (EncryptedSharedPreferences).
 */
class Store(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nvrdeck_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ένα κοινό στιγμιότυπο δεδομένων για όλη τη διεργασία (εφαρμογή + υπηρεσία ειδοποιήσεων)
    private val _data = synchronized(Store::class) { shared ?: MutableStateFlow(load()).also { shared = it } }
    private companion object { var shared: MutableStateFlow<StoreData>? = null }
    val data: StateFlow<StoreData> = _data

    private fun load(): StoreData =
        prefs.getString("data", null)?.let { runCatching { json.decodeFromString<StoreData>(it) }.getOrNull() } ?: StoreData()

    fun update(f: (StoreData) -> StoreData) {
        val n = f(_data.value)
        _data.value = n
        prefs.edit().putString("data", json.encodeToString(StoreData.serializer(), n)).apply()
    }

    val hasPin get() = _data.value.pinHash.isNotEmpty()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
        update { it.copy(pinSalt = salt, pinHash = hash(salt, pin)) }
    }

    fun checkPin(pin: String) = hash(_data.value.pinSalt, pin) == _data.value.pinHash

    private fun hash(salt: String, pin: String): String {
        var out = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        repeat(50_000) { out = MessageDigest.getInstance("SHA-256").digest(out + salt.toByteArray()) }
        return out.hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
