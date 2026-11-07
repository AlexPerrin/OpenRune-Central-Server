package dev.or2.central.signin

import dev.or2.central.auth.CompositePasswordHasher
import dev.or2.central.config.SignInConfig
import dev.or2.central.db.repositories.AccountRepository
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInServiceTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private var now = 1_800_000_000L
    private val accounts =
        AccountRepository(
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> error("no database") } as DataSource,
        )

    private fun service(tokenSecret: String = secret) =
        SignInService(SignInConfig(tokenSecret = tokenSecret), accounts, CompositePasswordHasher("bcrypt", 4)) { now }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `login token is the compact form the game verifies`() {
        val token = service().mintLoginToken("abcdefghijkl")
        assertTrue(token.length <= 67)
        val body = token.substringBeforeLast('.')
        assertEquals("abcdefghijkl.${now + 3600}", body)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256")) }
        assertEquals(b64(mac.doFinal(body.toByteArray())), token.substringAfterLast('.'))
    }

    @Test
    fun `a code is single use and bound to its PKCE challenge`() {
        val signIn = service()
        val verifier = "verifier-123"
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val code = signIn.issueCode("tester", "n1", challenge)
        assertNull(signIn.redeemCode(code, "wrong"))
        val again = signIn.issueCode("tester", "n1", challenge)
        assertEquals(SignedIn("tester", "n1"), signIn.redeemCode(again, verifier))
        assertNull(signIn.redeemCode(again, verifier))
        assertNull(signIn.redeemCode("unknown", verifier))
    }

    @Test
    fun `a session opens only for an id token Central signed and not expired`() {
        val signIn = service()
        val idToken = signIn.idToken("http://127.0.0.1:8080/", "client", SignedIn("tester", "n1"))
        val session = assertNotNull(signIn.openSession(idToken))
        assertEquals("tester", signIn.sessionAccount(session))
        assertNull(signIn.sessionAccount("unknown"))

        val forged = service(Base64.getEncoder().encodeToString(ByteArray(32))).idToken("x", "client", SignedIn("tester", null))
        assertNull(signIn.openSession(forged))
        val parts = idToken.split('.')
        val altered = parts[0] + "." + b64("""{"sub":"admin","exp":${now + 100}}""".toByteArray()) + "." + parts[2]
        assertNull(signIn.openSession(altered))
        now += 86_401
        assertNull(signIn.openSession(idToken))
    }
}
