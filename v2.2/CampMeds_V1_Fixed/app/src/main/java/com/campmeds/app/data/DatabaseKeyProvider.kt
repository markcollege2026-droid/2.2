package com.campmeds.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates (once) and retrieves the SQLCipher database passphrase.
 *
 * The passphrase itself is random, high-entropy bytes — it is never derived from anything
 * guessable. It's stored in an EncryptedSharedPreferences file whose own encryption key lives
 * in the Android Keystore (hardware-backed on most devices), so the passphrase never touches
 * disk in plaintext. This satisfies "encrypted at rest ... with the key stored in the Android
 * Keystore" (spec section 2 / 7) without hand-rolling Keystore key wrapping.
 */
object DatabaseKeyProvider {

    private const val PREFS_FILE = "campmeds_db_key_store"
    private const val KEY_ALIAS = "db_passphrase"

    fun getOrCreatePassphrase(context: Context): CharArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // BUG FIX (V1 bug 1): the stored Base64 string IS the passphrase. It must be returned in
        // exactly the same form on first creation and on every later launch. The old code returned
        // the Base64 text on first run but decoded it to raw bytes on later runs, so SQLCipher got
        // a different password after restart and could not reopen the database.
        val existing = prefs.getString(KEY_ALIAS, null)
        if (existing != null) {
            return existing.toCharArray()
        }

        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val encoded = Base64.getEncoder().encodeToString(randomBytes)

        // commit() (synchronous) rather than apply(): the DB is about to be created with this key,
        // so the key must be durably stored before we hand it out.
        check(prefs.edit().putString(KEY_ALIAS, encoded).commit()) { "Could not persist database key" }
        return encoded.toCharArray()
    }
}
