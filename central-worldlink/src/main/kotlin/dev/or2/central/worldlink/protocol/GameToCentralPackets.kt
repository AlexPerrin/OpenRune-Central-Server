package dev.or2.central.worldlink.protocol

/** Encodes game-server → Central world-link frames. */
object GameToCentralPackets {
    fun worldHello(worldId: Int, worldKey: ByteArray): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_HELLO) {
            writeInt(WorldOpcodes.MAGIC)
            writeShort(WorldOpcodes.PROTOCOL_VERSION)
            writeInt(worldId)
            writeShort(worldKey.size)
            writeBytes(worldKey)
        }

    fun login(username: String, password: CharArray, loginCharacterId: Int?): ByteArray =
        outboundFrame(WorldOpcodes.OP_LOGIN) {
            writeUtf8LenPrefixed(username)
            writeUtf8LenPrefixed(password.concatToString())
            val cid = loginCharacterId?.takeIf { it > 0 }
            if (cid != null && WorldOpcodes.PROTOCOL_VERSION >= 4) {
                writeInt(cid)
            }
        }

    fun tokenLogin(username: String, loginCharacterId: Int?): ByteArray =
        outboundFrame(WorldOpcodes.OP_TOKEN_LOGIN) {
            writeUtf8LenPrefixed(username)
            loginCharacterId?.takeIf { it > 0 }?.let { writeInt(it) }
        }

    fun logout(sessionToken: ByteArray): ByteArray =
        outboundFrame(WorldOpcodes.OP_LOGOUT) {
            require(sessionToken.size == WorldOpcodes.TOKEN_BYTES)
            writeShort(sessionToken.size)
            writeBytes(sessionToken)
        }

    fun heartbeat(sessionToken: ByteArray): ByteArray =
        outboundFrame(WorldOpcodes.OP_HEARTBEAT) {
            require(sessionToken.size == WorldOpcodes.TOKEN_BYTES)
            writeShort(sessionToken.size)
            writeBytes(sessionToken)
        }

    fun pushSubscribe(): ByteArray = byteArrayOf(WorldOpcodes.OP_PUSH_SUBSCRIBE.toByte())
}
