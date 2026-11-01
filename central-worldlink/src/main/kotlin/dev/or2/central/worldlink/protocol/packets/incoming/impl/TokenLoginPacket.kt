package dev.or2.central.worldlink.protocol.packets.incoming.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.InboundPacket
import dev.or2.central.worldlink.protocol.PacketDecodeException
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketIncoming

@WorldPacketIncoming(
    opcode = WorldOpcodes.OP_TOKEN_LOGIN,
    name = "TOKEN_LOGIN",
    fields = [
        FieldKind.STRING_LOGIN_USERNAME,
        FieldKind.INT_OPTIONAL,
    ],
)
object TokenLoginPacket : InboundPacket<TokenLoginPacket.Payload> {
    data class Payload(
        val username: String,
        val characterId: Int?,
    )

    override fun decode(input: FrameReader): Payload {
        val username = input.readUtf8LenPrefixed()
        val characterId =
            when (val trailing = input.trailingUnreadBytes()) {
                0 -> null
                4 -> input.readInt()
                else -> throw PacketDecodeException("invalid token login trailer length: $trailing")
            }
        input.requireFullyConsumed()
        return Payload(username, characterId)
    }
}
