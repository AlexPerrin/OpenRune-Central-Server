package dev.or2.central.worldlink.handlers

import dev.or2.central.session.LoginException
import dev.or2.central.session.SessionService
import dev.or2.central.social.SocialService
import dev.or2.central.worldlink.WorldConnection
import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.PacketDecodeException
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.packets.incoming.impl.LoginPacket
import dev.or2.central.worldlink.protocol.packets.incoming.impl.TokenLoginPacket
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.LoginFailPacket
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.LoginOkPacket

class LoginHandler(
    private val sessionService: SessionService,
    private val socialService: SocialService,
) {
    fun handle(connection: WorldConnection, input: FrameReader): HandlerResult {
        if (!connection.handshakeDone) {
            return fail(WorldOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN)
        }
        val payload =
            try {
                LoginPacket.decode(input)
            } catch (_: PacketDecodeException) {
                return fail(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)
            }
        val loginCharacterId =
            when {
                payload.characterId == null -> null
                connection.protocolVersion >= 4 -> payload.characterId
                else -> return fail(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)
            }
        return login(connection, payload.username, payload.password, loginCharacterId)
    }

    fun handleToken(connection: WorldConnection, input: FrameReader): HandlerResult {
        if (!connection.handshakeDone) {
            return fail(WorldOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN)
        }
        if (connection.protocolVersion < 9) {
            return fail(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)
        }
        val payload =
            try {
                TokenLoginPacket.decode(input)
            } catch (_: PacketDecodeException) {
                return fail(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)
            }
        return login(connection, payload.username, null, payload.characterId)
    }

    private fun login(
        connection: WorldConnection,
        username: String,
        password: String?,
        loginCharacterId: Int?,
    ): HandlerResult {
        val result = sessionService.login(connection, username, password, loginCharacterId)
        result.fold(
            onSuccess = { login ->
                socialService.onCharacterOnline(connection.worldId, loginCharacterId?.takeIf { it > 0 }, login.accountId)
                return HandlerResult.Reply(
                    LoginOkPacket.encode(
                        LoginOkPacket.Payload(
                            token = login.token,
                            accountId = login.accountId,
                            rights = login.rights,
                            realmDevMode = login.realmDevMode,
                            clientProtocolVersion = connection.protocolVersion,
                        ),
                    ),
                )
            },
            onFailure = { ex ->
                if (ex is LoginException) {
                    return HandlerResult.Reply(
                        LoginFailPacket.encode(
                            LoginFailPacket.Payload(
                                code = ex.code,
                                clientProtocolVersion = connection.protocolVersion,
                                scriptLines = ex.scriptLines,
                            ),
                        ),
                    )
                }
                return fail(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)
            },
        )
    }

    private fun fail(code: Int): HandlerResult = HandlerResult.Reply(LoginFailPacket.encode(LoginFailPacket.Payload(code)))
}
