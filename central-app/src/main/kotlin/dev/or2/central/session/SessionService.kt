package dev.or2.central.session

import dev.or2.central.account.AccountNameAuthPolicy
import dev.or2.central.account.AccountNameWorldLinkPrecheck
import dev.or2.central.account.AccountNameWorldLinkPrecheckResult
import dev.or2.central.account.Rights
import dev.or2.central.auth.PasswordHasher
import dev.or2.central.db.repositories.AccountRepository
import dev.or2.central.db.repositories.PunishmentLoginBlock
import dev.or2.central.db.repositories.PunishmentService
import dev.or2.central.db.repositories.SessionRepository
import dev.or2.central.db.repositories.WorldOperationRepository
import dev.or2.central.db.repositories.WorldRepository
import dev.or2.central.http.WorldListCache
import dev.or2.central.social.SocialService
import dev.or2.central.util.sha256
import dev.or2.central.worldlink.WorldConnection
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.sql.OpenRuneSql
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Types
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

data class LoginResult(
    val token: ByteArray,
    val accountId: Long,
    val rights: Rights,
    val realmDevMode: Boolean,
)

class SessionService(
    private val dataSource: DataSource,
    private val accountRepository: AccountRepository,
    private val sessionRepository: SessionRepository,
    private val punishmentService: PunishmentService,
    private val worldRepository: WorldRepository,
    private val worldOperationRepository: WorldOperationRepository,
    private val passwordHasher: PasswordHasher,
    private val worldListCache: WorldListCache?,
    private val socialService: SocialService,
    private val badWordRoots: () -> Set<String> = { emptySet() },
    private val loginTimingLogs: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(SessionService::class.java)
    private val random = SecureRandom()
    private val passwordRehashExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "central-password-rehash").apply { isDaemon = true }
        }

    fun login(
        connection: WorldConnection,
        username: String,
        password: String?,
        loginCharacterId: Int?,
    ): Result<LoginResult> {
        val loginStartedAt = System.nanoTime()
        var verifyMs = 0L
        var sessionTxMs = 0L
        if (password != null && password.length > 256) {
            return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        val roots = badWordRoots()
        val canonical =
            when (val pre = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin(username, roots)) {
                AccountNameWorldLinkPrecheckResult.BadFrame ->
                    return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_BAD_FRAME))
                is AccountNameWorldLinkPrecheckResult.Policy -> {
                    val lines = AccountNameAuthPolicy.policyScriptLines(pre.reason)
                    return Result.failure(
                        LoginException(
                            WorldOpcodes.LOGIN_FAIL_WORLD_ACCESS,
                            scriptLines = lines,
                        ),
                    )
                }
                is AccountNameWorldLinkPrecheckResult.Ok -> pre.canonical
            }
        val loginKey = canonical.lowercase()
        var account = accountRepository.findByUsername(loginKey)
        val registerNow = System.currentTimeMillis()
        var registeredThisLogin = false
        if (account == null) {
            val ck = AccountNameAuthPolicy.collisionKey(canonical)
            if (ck.isNotEmpty() && accountRepository.collisionKeyTaken(ck)) {
                return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_INVALID))
            }
            val hash = password?.let { passwordHasher.hash(it) } ?: ""
            val inserted = accountRepository.insertIfAbsent(canonical, hash, registerNow)
            account = accountRepository.findByUsername(loginKey)
            registeredThisLogin = inserted && account != null
        }
        val verifyStart = System.nanoTime()
        val passwordOk =
            when {
                account == null -> false
                registeredThisLogin || password == null -> true
                else -> passwordHasher.verify(account.passwordHash, password)
            }
        verifyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - verifyStart)
        if (!passwordOk) {
            logLoginTiming(username, loginStartedAt, verifyMs, sessionTxMs, "invalid")
            return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_INVALID))
        }
        val resolvedAccount = account!!
        val effectiveCharId = loginCharacterId?.takeIf { it > 0 }
        if (effectiveCharId != null &&
            !punishmentService.characterBelongsToAccount(effectiveCharId, resolvedAccount.id)
        ) {
            return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        when (punishmentService.blockingLoginSanction(resolvedAccount.id, effectiveCharId)) {
            PunishmentLoginBlock.LOCKED ->
                return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_LOCKED))
            PunishmentLoginBlock.BANNED ->
                return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_BANNED))
            null -> Unit
        }
        if (connection.loginRestrictionsEnabled) {
            evaluateWorldLoginDeny(connection, loginKey, resolvedAccount.rights, resolvedAccount.id, effectiveCharId)?.let {
                return Result.failure(it)
            }
        }
        if (worldOperationRepository.shouldBlockLoginForScheduledReboot(connection.worldId, System.currentTimeMillis())) {
            return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_UPDATE_IN_PROGRESS))
        }
        val max = connection.maxPlayers
        val token = ByteArray(WorldOpcodes.TOKEN_BYTES).also { random.nextBytes(it) }
        val tokenHash = sha256(token)
        val now = System.currentTimeMillis()
        val sessionTxStart = System.nanoTime()
        try {
            for (existing in sessionRepository.listByAccountId(resolvedAccount.id)) {
                val characterId = existing.characterId ?: continue
                socialService.onCharacterOffline(existing.worldId, characterId)
            }
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    deleteSessionsForAccount(conn, resolvedAccount.id)
                    if (max != null && countSessionsForWorld(conn, connection.worldId) >= max) {
                        conn.rollback()
                        return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_WORLD_FULL))
                    }
                    insertSession(conn, resolvedAccount.id, connection.worldId, effectiveCharId, tokenHash, now)
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (e: Exception) {
            log.warn("Login transaction failed (worldId={})", connection.worldId, e)
            logLoginTiming(username, loginStartedAt, verifyMs, sessionTxMs, "tx-failed")
            return Result.failure(LoginException(WorldOpcodes.LOGIN_FAIL_BAD_FRAME))
        } finally {
            sessionTxMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sessionTxStart)
        }
        sessionRepository.invalidateCache()
        worldListCache?.rebuildAsync()
        if (password != null) {
            maybeScheduleDevPasswordRehash(connection, resolvedAccount.id, resolvedAccount.passwordHash, password)
        }
        val rights = resolvedAccount.rights
        logLoginTiming(username, loginStartedAt, verifyMs, sessionTxMs, "ok")
        return Result.success(LoginResult(token, resolvedAccount.id, rights, connection.realmDevMode))
    }

    private fun logLoginTiming(
        username: String,
        startedAtNanos: Long,
        verifyMs: Long,
        sessionTxMs: Long,
        outcome: String,
    ) {
        if (!loginTimingLogs) {
            return
        }
        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        if (totalMs < LOGIN_TIMING_INFO_MS && verifyMs < LOGIN_TIMING_INFO_MS) {
            return
        }
        log.info(
            "Central LOGIN detail user={} outcome={} verify={}ms sessionTx={}ms total={}ms",
            username,
            outcome,
            verifyMs,
            sessionTxMs,
            totalMs,
        )
    }

    private fun maybeScheduleDevPasswordRehash(
        connection: WorldConnection,
        accountId: Long,
        storedHash: String,
        password: String,
    ) {
        if (!connection.realmDevMode || !passwordHasher.shouldUpgrade(storedHash)) {
            return
        }
        passwordRehashExecutor.execute {
            try {
                accountRepository.updatePasswordHash(accountId, passwordHasher.hash(password))
                log.info("Central dev password hash upgraded for accountId={}", accountId)
            } catch (e: Exception) {
                log.warn("Central dev password rehash failed for accountId={}", accountId, e)
            }
        }
    }

    fun logoutFast(token: ByteArray, worldId: Int): Boolean {
        val row = sessionRepository.findByTokenHash(sha256(token)) ?: return false
        if (row.worldId != worldId) return false
        sessionRepository.deleteById(row.id)
        worldListCache?.rebuildAsync()
        return true
    }

    fun touchSession(token: ByteArray, worldId: Int): Boolean {
        val row = sessionRepository.findByTokenHash(sha256(token)) ?: return false
        if (row.worldId != worldId) return false
        sessionRepository.touchById(row.id, System.currentTimeMillis())
        return true
    }

    private fun deleteSessionsForAccount(conn: Connection, accountId: Long) {
        val sql = OpenRuneSql.text("central/session/delete_by_account_id.sql")
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, accountId)
            ps.executeUpdate()
        }
    }

    private fun countSessionsForWorld(conn: Connection, worldId: Int): Int {
        val sql = OpenRuneSql.text("central/session/count_for_world.sql")
        return conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, worldId)
            ps.executeQuery().use { rs ->
                require(rs.next())
                rs.getInt(1)
            }
        }
    }

    private fun insertSession(
        conn: Connection,
        accountId: Long,
        worldId: Int,
        characterId: Int?,
        tokenHash: ByteArray,
        now: Long,
    ) {
        val sql = OpenRuneSql.text("central/session/insert.sql")
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, accountId)
            ps.setInt(2, worldId)
            if (characterId != null) {
                ps.setInt(3, characterId)
            } else {
                ps.setNull(3, Types.INTEGER)
            }
            ps.setBytes(4, tokenHash)
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.executeUpdate()
        }
    }

    private data class WorldGateDeny(val code: Int, val line1: String, val line2: String, val line3: String)

    private fun evaluateWorldLoginDeny(
        connection: WorldConnection,
        loginUsername: String,
        accountRights: Rights,
        accountId: Long,
        characterId: Int?,
    ): LoginException? {
        if (!connection.loginRestrictionsEnabled) return null
        val minRequired = connection.loginMinTotalLevel.coerceAtLeast(0)
        val anyLevel = connection.loginGateMinLevelEnabled && minRequired > 0
        val anyRights = connection.loginGateRightsEnabled && !connection.loginMinRightsToken.isNullOrBlank()
        val hasWhitelistRows = worldRepository.hasLoginWhitelistEntries(connection.worldId)
        val anyWhitelist = connection.loginGateWhitelistEnabled && hasWhitelistRows
        if (!anyLevel && !anyRights && !anyWhitelist) return null
        var ok = false
        val total =
            if (anyLevel && characterId != null) {
                worldRepository.characterTotalBaseLevel(characterId, accountId)
            } else {
                null
            }
        if (anyLevel && characterId != null && (total ?: 0) >= minRequired) ok = true
        if (!ok && anyWhitelist && worldRepository.isLoginWhitelisted(connection.worldId, loginUsername)) ok = true
        if (!ok && anyRights) {
            val t = connection.loginMinRightsToken!!
            if (accountRights.satisfiesGate(Rights.parse(t))) ok = true
        }
        if (ok) return null
        if (anyLevel && (characterId == null || (total ?: 0) < minRequired)) {
            return LoginException(
                WorldOpcodes.LOGIN_FAIL_WORLD_MIN_LEVEL,
                Triple(
                    "You need a total level of at least $minRequired.",
                    if (characterId == null) {
                        "This world could not verify your character's skills on login."
                    } else {
                        "Your total level is ${total ?: 0}."
                    },
                    if (characterId == null) {
                        "Select your character before logging in, or update your game server."
                    } else {
                        "Train your skills, then try again."
                    },
                ),
            )
        }
        if (anyWhitelist) {
            return LoginException(
                WorldOpcodes.LOGIN_FAIL_WORLD_WHITELIST,
                Triple(
                    "This world is restricted.",
                    "Your account is not on the login whitelist.",
                    "Contact staff if you believe this is a mistake.",
                ),
            )
        }
        if (anyRights) {
            val tok = (connection.loginMinRightsToken ?: "").trim()
            return LoginException(
                WorldOpcodes.LOGIN_FAIL_WORLD_RIGHTS,
                Triple(
                    "This world requires special account access.",
                    """You need "$tok" access to log in.""",
                    "",
                ),
            )
        }
        return LoginException(
            WorldOpcodes.LOGIN_FAIL_WORLD_ACCESS,
            Triple(
                "You cannot log in to this world right now.",
                "World login rules blocked this account.",
                "",
            ),
        )
    }
}

private const val LOGIN_TIMING_INFO_MS = 100L

class LoginException(
    val code: Int,
    val scriptLines: Triple<String, String, String>? = null,
) : Exception("login fail $code")
