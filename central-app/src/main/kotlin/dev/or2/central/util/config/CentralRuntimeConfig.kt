package dev.or2.central.util.config

import dev.or2.central.config.AnalyticsConfig
import dev.or2.central.config.BadWordsConfig
import dev.or2.central.config.CentralConfig
import dev.or2.central.config.DatabaseConfig
import dev.or2.central.config.HttpConfig
import dev.or2.central.config.JavConfigSettings
import dev.or2.central.config.JdbcConfig
import dev.or2.central.config.SessionConfig
import dev.or2.central.config.SignInConfig
import dev.or2.central.config.TlsConfig
import dev.or2.central.config.WorldLinkConfig

/** Builds [CentralConfig] for embedded Central inside the game server process. */
fun centralRuntimeConfigFromJdbc(
    jdbcUrl: String,
    dbUser: String,
    dbPassword: String,
    dbMaximumPoolSize: Int,
    worldLinkPort: Int,
    httpPort: Int = 8080,
    serverName: String = "OpenRune",
    worldLinkSoBacklog: Int = 512,
    sessionsTtlMillis: Long = 300_000L,
    worldsLinkReadTimeoutSeconds: Int = 120,
    onlineSampleIntervalSeconds: Int = 3600,
    badWordsRemoteUrl: String =
        "https://gist.githubusercontent.com/briankung/e085841a7a13fa4945a0cf482950436a/raw/326b4078db98541204e3d192d7cf84f63cd4c87a/bad_words.txt",
    badWordsRefreshMinutes: Int = 360,
    javConfigRevision: Int = 238,
    javConfigRemoteUrlTemplate: String =
        System.getenv("OPENRUNE_JAV_CONFIG_URL_TEMPLATE")?.takeIf { it.isNotBlank() }
            ?: "https://client.blurite.io/jav_local_%d.ws",
    javConfigProps: Map<String, String> = emptyMap(),
    javConfigRefreshMinutes: Int = 15,
    javConfigHttpTimeoutSeconds: Int = 20,
    httpTrustProxy: Boolean = false,
    devWorldAutoCreate: Boolean = true,
    loginTimingLogs: Boolean = false,
    socialPmTraceLogs: Boolean = false,
    httpTls: TlsConfig = CentralConfig.fromEnvironment(),
    signIn: SignInConfig = CentralConfig.fromEnvironment(),
): CentralConfig {
    require(jdbcUrl.isNotBlank()) { "jdbcUrl is required" }
    require(dbUser.isNotBlank()) { "dbUser is required" }

    return CentralConfig(
        serverName = serverName,
        database =
            DatabaseConfig(
                host = "",
                name = "",
                user = dbUser,
                password = dbPassword,
                poolSize = dbMaximumPoolSize.coerceIn(1, 100),
            ),
        jdbc = JdbcConfig(url = jdbcUrl.trim(), user = dbUser.trim(), password = dbPassword),
        http = HttpConfig(port = httpPort.coerceIn(1, 65535), trustProxy = httpTrustProxy, tls = httpTls),
        worldLink =
            WorldLinkConfig(
                port = worldLinkPort.coerceIn(1, 65535),
                soBacklog = worldLinkSoBacklog.coerceIn(64, 16_384),
                readTimeoutSeconds = worldsLinkReadTimeoutSeconds.coerceIn(5, 3600),
            ),
        session = SessionConfig(ttlMs = sessionsTtlMillis.coerceAtLeast(1L)),
        analytics = AnalyticsConfig(onlineSampleIntervalSeconds = onlineSampleIntervalSeconds.coerceIn(30, 86_400)),
        javConfig =
            JavConfigSettings(
                revision = javConfigRevision.coerceAtLeast(1),
                remoteUrlTemplate = javConfigRemoteUrlTemplate.trim(),
                configProps = javConfigProps,
                refreshMinutes = javConfigRefreshMinutes.coerceIn(1, 10_080),
                httpTimeoutSeconds = javConfigHttpTimeoutSeconds.coerceIn(3, 120),
            ),
        badWords =
            BadWordsConfig(
                remoteUrl = badWordsRemoteUrl.trim(),
                refreshMinutes = badWordsRefreshMinutes.coerceIn(5, 10_080),
            ),
        devWorld = dev.or2.central.config.DevWorldConfig(autoCreate = devWorldAutoCreate),
        diagnostics =
            dev.or2.central.config.DiagnosticsConfig(
                loginTimingLogs = loginTimingLogs,
                socialPmTraceLogs = socialPmTraceLogs,
            ),
        signIn = signIn,
    )
}
