package dev.or2.central.signin

import dev.or2.central.auth.PasswordHasher
import dev.or2.central.config.SignInConfig
import dev.or2.central.db.repositories.AccountRepository
import dev.or2.central.util.constantTimeEquals
import dev.or2.central.util.sha256
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class SignedIn(
    val account: String,
    val nonce: String?,
)

class SignInService(
    config: SignInConfig,
    private val accounts: AccountRepository,
    private val passwordHasher: PasswordHasher,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val devAccount: String = config.devAccount.trim()
    private val key = SecretKeySpec(Base64.getDecoder().decode(config.tokenSecret.trim()), "HmacSHA256")
    private val random = SecureRandom()
    private val codes = ConcurrentHashMap<String, Code>()
    private val refreshTokens = ConcurrentHashMap<String, String>()
    private val accessTokens = ConcurrentHashMap<String, String>()
    private val sessions = ConcurrentHashMap<String, String>()

    private class Code(
        val account: String,
        val nonce: String?,
        val challenge: String?,
        val issuedAt: Long,
    )

    fun checkPassword(username: String, password: String): String? {
        val account = accounts.findByUsername(username.trim()) ?: return null
        return account.username.takeIf { passwordHasher.verify(account.passwordHash, password) }
    }

    fun issueCode(account: String, nonce: String?, challenge: String?): String {
        val now = clock()
        codes.values.removeIf { now - it.issuedAt > CODE_TTL_SECONDS }
        return randomId().also { codes[it] = Code(account, nonce, challenge, now) }
    }

    fun redeemCode(code: String, verifier: String?): SignedIn? {
        val entry = codes.remove(code) ?: return null
        if (clock() - entry.issuedAt > CODE_TTL_SECONDS) return null
        if (entry.challenge != null && (verifier == null || b64(sha256(verifier.toByteArray())) != entry.challenge)) return null
        return SignedIn(entry.account, entry.nonce)
    }

    fun issueRefreshToken(account: String): String = randomId().also { refreshTokens[it] = account }

    fun redeemRefreshToken(token: String): String? = refreshTokens.remove(token)

    fun issueAccessToken(account: String): String = randomId().also { accessTokens[it] = account }

    fun accessAccount(token: String): String? = accessTokens[token]

    fun idToken(issuer: String, audience: String, signedIn: SignedIn): String {
        val now = clock()
        val claims =
            buildJsonObject {
                put("iss", issuer)
                put("sub", signedIn.account)
                put("aud", audience)
                put("exp", now + ID_TOKEN_TTL_SECONDS)
                put("iat", now)
                put("login_provider", "jagex")
                put("nickname", signedIn.account)
                signedIn.nonce?.let { put("nonce", it) }
            }
        val header = buildJsonObject {
            put("alg", "HS256")
            put("typ", "JWT")
        }
        val body = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
        return body + "." + b64(sign(body))
    }

    fun openSession(idToken: String): String? {
        val account = idTokenAccount(idToken) ?: return null
        return randomId().also { sessions[it] = account }
    }

    fun sessionAccount(sessionId: String): String? = sessions[sessionId]

    fun mintLoginToken(account: String): String {
        val body = "$account.${clock() + LOGIN_TOKEN_TTL_SECONDS}"
        return body + "." + b64(sign(body))
    }

    private fun idTokenAccount(idToken: String): String? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val signature =
            try {
                Base64.getUrlDecoder().decode(parts[2])
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (!constantTimeEquals(sign(parts[0] + "." + parts[1]), signature)) return null
        return try {
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
            claims.getValue("sub").jsonPrimitive.content.takeIf { claims.getValue("exp").jsonPrimitive.long > clock() }
        } catch (_: Exception) {
            null
        }
    }

    private fun sign(message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(message.toByteArray())
        }

    private fun randomId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val CODE_TTL_SECONDS = 600L
        private const val ID_TOKEN_TTL_SECONDS = 86_400L
        private const val LOGIN_TOKEN_TTL_SECONDS = 3_600L

        fun accountId(account: String): Long = CRC32().apply { update(account.toByteArray()) }.value and 0x7FFFFFFF

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
