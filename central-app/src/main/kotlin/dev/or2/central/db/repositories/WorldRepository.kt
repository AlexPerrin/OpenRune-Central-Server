package dev.or2.central.db.repositories

import dev.or2.sql.OpenRuneSql
import javax.sql.DataSource

data class WorldRow(
    val worldId: Int,
    val flags: String,
    val host: String,
    val activity: String,
    val location: Int,
    val population: Int,
) {
    val properties: Int
        get() = WorldFlag.maskFromCsv(flags)
}

data class WorldAuthRow(
    val worldId: Int,
    val enabled: Boolean,
    val maxPlayers: Int?,
    val worldKeySha256: ByteArray?,
    val loginRestrictionsEnabled: Boolean,
    val loginMinTotalLevel: Int,
    val loginMinRightsToken: String?,
    val loginGateMinLevelEnabled: Boolean,
    val loginGateRightsEnabled: Boolean,
    val loginGateWhitelistEnabled: Boolean,
    val realmDevMode: Boolean,
)

class WorldRepository(
    private val dataSource: DataSource,
) {
    fun findAllForList(): List<WorldRow> {
        val sql = OpenRuneSql.text("central/world/find_all_for_list.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorldRow(
                                    worldId = rs.getInt("world_id"),
                                    flags = rs.getString("flags") ?: "",
                                    host = rs.getString("host"),
                                    activity = rs.getString("activity"),
                                    location = rs.getInt("location"),
                                    population = rs.getInt("population"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun findForAuth(worldId: Int): WorldAuthRow? {
        val sql = OpenRuneSql.text("central/world/find_for_auth.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, worldId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val maxPlayers = rs.getObject("max_players") as Int?
                    WorldAuthRow(
                        worldId = rs.getInt("world_id"),
                        enabled = rs.getInt("enabled") != 0,
                        maxPlayers = maxPlayers,
                        worldKeySha256 = rs.getBytes("world_key_sha256"),
                        loginRestrictionsEnabled = rs.getInt("login_restrictions_enabled") != 0,
                        loginMinTotalLevel = rs.getInt("login_min_total_level"),
                        loginMinRightsToken = rs.getString("login_min_rights_token")?.trim()?.takeIf { it.isNotEmpty() },
                        loginGateMinLevelEnabled = rs.getInt("login_gate_min_level_enabled") != 0,
                        loginGateRightsEnabled = rs.getInt("login_gate_rights_enabled") != 0,
                        loginGateWhitelistEnabled = rs.getInt("login_gate_whitelist_enabled") != 0,
                        realmDevMode = rs.getBoolean("realm_dev_mode"),
                    )
                }
            }
        }
    }

    fun hasLoginWhitelistEntries(worldId: Int): Boolean {
        val sql = OpenRuneSql.text("game/world/login_whitelist_any.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, worldId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    fun isLoginWhitelisted(worldId: Int, loginUsername: String): Boolean {
        val sql = OpenRuneSql.text("game/world/login_whitelist_exists.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, worldId)
                ps.setString(2, loginUsername)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    fun characterTotalBaseLevel(characterId: Int, accountId: Long): Int? {
        val sql = OpenRuneSql.text("game/world/character_total_base_level.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.setLong(2, accountId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getInt(1)
                }
            }
        }
    }
}
