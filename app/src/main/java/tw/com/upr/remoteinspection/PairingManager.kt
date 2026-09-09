package tw.com.upr.remoteinspection

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** One-device pairing: a short-lived PIN exchanges for a persistent random token. */
class PairingManager(context: Context) {
    private val preferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private var pin: String? = null
    private var pinExpiresAt = 0L
    private var failedAttempts = 0

    @Synchronized fun hasPairedClient(): Boolean = preferences.getStringSet(TOKEN_HASHES, emptySet()).orEmpty().isNotEmpty()

    @Synchronized fun createPin(resetExisting: Boolean = false): String {
        if (resetExisting) preferences.edit().remove(TOKEN_HASHES).apply()
        val value = "%06d".format(random.nextInt(1_000_000))
        pin = value
        pinExpiresAt = System.currentTimeMillis() + PIN_LIFETIME_MS
        failedAttempts = 0
        return value
    }

    @Synchronized fun currentPin(): String? = pin?.takeIf { System.currentTimeMillis() < pinExpiresAt }

    @Synchronized fun pair(candidate: String): String? {
        val activePin = currentPin() ?: return null
        if (!MessageDigest.isEqual(activePin.toByteArray(), candidate.trim().toByteArray())) {
            failedAttempts++
            if (failedAttempts >= MAX_PIN_ATTEMPTS) {
                pin = null
                pinExpiresAt = 0L
            }
            return null
        }
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val hashes = preferences.getStringSet(TOKEN_HASHES, emptySet()).orEmpty().toMutableSet()
        hashes += hash(token)
        preferences.edit().putStringSet(TOKEN_HASHES, hashes).apply()
        pin = null
        pinExpiresAt = 0L
        return token
    }

    fun isAuthorized(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val candidateHash = hash(token).toByteArray()
        return preferences.getStringSet(TOKEN_HASHES, emptySet()).orEmpty()
            .any { MessageDigest.isEqual(it.toByteArray(), candidateHash) }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TOKEN_HASHES = "controller_token_sha256_set"
        private const val PIN_LIFETIME_MS = 5 * 60 * 1000L
        private const val MAX_PIN_ATTEMPTS = 10
    }
}
