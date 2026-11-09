package dev.or2.central.social

import dev.or2.central.worldlink.WorldConnectionRegistry
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.social.FriendPresencePush
import dev.or2.central.worldlink.protocol.social.PmTraceLog
import dev.or2.central.worldlink.protocol.social.PrivateMessagePush
import dev.or2.central.worldlink.protocol.social.SocialPackets
import dev.or2.central.worldlink.protocol.social.SocialSyncFriend
import dev.or2.central.worldlink.protocol.social.SocialSyncIgnore
import dev.or2.central.worldlink.protocol.social.SocialSyncSnapshot
import org.slf4j.LoggerFactory

class SocialService(
    private val graph: SocialGraphStore,
    private val onlineIndex: OnlinePresenceIndex,
    private val persistence: SocialGraphPersistence,
    private val repository: CentralSocialRepository,
    private val presence: SocialPresenceResolver,
    private val registry: WorldConnectionRegistry,
    private val pmTraceLogs: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(SocialService::class.java)

    fun onCharacterOnline(
        worldId: Int,
        characterId: Int?,
        accountId: Long,
    ) {
        if (characterId == null || characterId <= 0) {
            return
        }
        onlineIndex.put(characterId, worldId, accountId)
        fanoutFriendPresence(characterId, worldId)
    }

    fun onCharacterOffline(
        worldId: Int,
        characterId: Int?,
    ) {
        if (characterId == null || characterId <= 0) {
            return
        }
        val recipients = graph.friendPresenceRecipientsForOffline(characterId, worldId, onlineIndex)
        for (recipient in recipients) {
            sendFriendPresence(recipient)
        }
        onlineIndex.remove(characterId)
    }

    fun handlePmRelay(
        worldId: Int,
        payload: SocialPackets.PmRelayPayload,
    ): SocialReply {
        if (!authorizeWorldSocial(worldId, payload.fromCharacterId)) {
            return socialFail()
        }
        val fromCharacterId = payload.fromCharacterId
        val targetName = payload.targetName
        val senderDisplayName = payload.senderDisplayName
        val message = payload.message

        if (targetName.isBlank() || message.isBlank()) {
            return socialFail()
        }

        if (pmTraceLogs) {
            log.info(
                PmTraceLog.format(
                    stage = PmTraceLog.STAGE_CENTRAL_RECV,
                    fromCharacterId = fromCharacterId,
                    targetName = targetName,
                    messageLen = message.length,
                    extra = "sourceWorld=$worldId",
                ),
            )
        }

        val targetCharacterId = resolveCharacterId(targetName)
            ?: return socialFail(WorldOpcodes.SOCIAL_FAIL_USER_NOT_FOUND)

        if (targetCharacterId == fromCharacterId) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_SELF_ACTION)
        }

        if (graph.isIgnored(targetCharacterId, fromCharacterId)) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }

        val filters = graph.chatFilters(targetCharacterId)
        if (filters.privateChat >= 2) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }
        if (filters.privateChat == 1 && !graph.isFriend(targetCharacterId, fromCharacterId)) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }

        val targetWorldId =
            presence.worldForPmDelivery(targetCharacterId)
                ?: return socialFail(WorldOpcodes.SOCIAL_FAIL_NOT_LOGGED_IN)

        val frame =
            SocialPackets.encodeServerPrivateMessage(
                PrivateMessagePush(
                    senderWorldId = worldId,
                    fromCharacterId = fromCharacterId,
                    toCharacterId = targetCharacterId,
                    senderCrown = payload.senderCrown,
                    senderDisplayName = senderDisplayName.ifBlank { "Player" },
                    message = message,
                ),
            )

        val pushed = registry.push(worldId = targetWorldId, frame)
        if (pmTraceLogs) {
            log.info(
                PmTraceLog.format(
                    stage =
                        if (pushed) {
                            PmTraceLog.STAGE_CENTRAL_PUSH
                        } else {
                            PmTraceLog.STAGE_CENTRAL_PUSH_FAIL
                        },
                    fromCharacterId = fromCharacterId,
                    toCharacterId = targetCharacterId,
                    targetName = targetName,
                    messageLen = message.length,
                    extra = "targetWorld=$targetWorldId",
                ),
            )
        }
        if (!pushed) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_NOT_LOGGED_IN)
        }

        return SocialReply.Ok(SocialPackets.encodeSocialOk())
    }

    fun handleFriendAdd(
        worldId: Int,
        payload: SocialPackets.NameActionPayload,
    ): SocialReply = handleNameAction(worldId, payload, SocialNameAction.FriendAdd)

    fun handleFriendDel(
        worldId: Int,
        payload: SocialPackets.NameActionPayload,
    ): SocialReply = handleNameAction(worldId, payload, SocialNameAction.FriendDel)

    fun handleIgnoreAdd(
        worldId: Int,
        payload: SocialPackets.NameActionPayload,
    ): SocialReply = handleNameAction(worldId, payload, SocialNameAction.IgnoreAdd)

    fun handleIgnoreDel(
        worldId: Int,
        payload: SocialPackets.NameActionPayload,
    ): SocialReply = handleNameAction(worldId, payload, SocialNameAction.IgnoreDel)

    fun handleChatFilters(
        worldId: Int,
        payload: SocialPackets.ChatFiltersPayload,
    ): SocialReply {
        if (!authorizeWorldSocial(worldId, payload.characterId)) {
            return socialFail()
        }
        val characterId = payload.characterId
        val friendWorld = onlineIndex.worldFor(characterId) ?: 0

        val beforeVisible = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
        graph.setPrivateChatFilter(characterId, payload.privateChat)
        persistence.enqueuePrivateChatFilter(characterId, payload.privateChat)
        val afterVisible = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
        sendPresenceVisibilityChanges(beforeVisible, afterVisible)

        return SocialReply.Ok(SocialPackets.encodeSocialOk())
    }

    fun handleSocialSync(
        worldId: Int,
        payload: SocialPackets.CharacterPayload,
    ): SocialReply {
        pruneStalePresence()
        if (!authorizeWorldSocial(worldId, payload.characterId)) {
            return socialSyncFail()
        }
        val characterId = payload.characterId
        val filters = graph.chatFilters(characterId)
        return SocialReply.Ok(
            SocialPackets.encodeSocialSyncOk(
                SocialSyncSnapshot(
                    publicChat = filters.publicChat,
                    privateChat = filters.privateChat,
                    tradeChat = filters.tradeChat,
                    friends =
                        graph.snapshotFriends(characterId, ::friendWorldForSnapshot).map {
                            SocialSyncFriend(it.displayName, it.previousDisplayName, it.worldId)
                        },
                    ignores =
                        graph.snapshotIgnores(characterId).map {
                            SocialSyncIgnore(it.displayName, it.previousDisplayName)
                        },
                ),
            ),
        )
    }

    fun updateDisplayName(
        characterId: Int,
        displayName: String?,
        previousDisplayName: String?,
    ) {
        if (characterId <= 0 || displayName.isNullOrBlank()) {
            return
        }
        graph.putCharacterName(characterId, displayName, previousDisplayName)
    }

    private enum class SocialNameAction {
        FriendAdd,
        FriendDel,
        IgnoreAdd,
        IgnoreDel,
    }

    private fun handleNameAction(
        worldId: Int,
        payload: SocialPackets.NameActionPayload,
        action: SocialNameAction,
    ): SocialReply {
        if (!authorizeWorldSocial(worldId, payload.characterId)) {
            log.warn(
                "Social {} failed: world social denied for characterId={} worldId={}",
                action,
                payload.characterId,
                worldId,
            )
            return socialFail()
        }
        val characterId = payload.characterId
        val targetName = payload.targetName
        if (targetName.isBlank()) {
            log.warn("Social {} failed: blank target name (characterId={})", action, characterId)
            return socialFail()
        }

        val targetCharacterId = resolveCharacterId(targetName)
        if (targetCharacterId == null) {
            log.warn("Social {} failed: target '{}' not found (characterId={})", action, targetName, characterId)
            return socialFail(WorldOpcodes.SOCIAL_FAIL_USER_NOT_FOUND)
        }

        if (targetCharacterId == characterId) {
            return socialFail(WorldOpcodes.SOCIAL_FAIL_SELF_ACTION)
        }

        val friendWorld = onlineIndex.worldFor(characterId) ?: 0

        return when (action) {
            SocialNameAction.FriendAdd -> {
                if (graph.isFriend(characterId, targetCharacterId)) {
                    socialFail(WorldOpcodes.SOCIAL_FAIL_ALREADY_FRIEND)
                } else if (graph.isIgnored(characterId, targetCharacterId)) {
                    socialFail(WorldOpcodes.SOCIAL_FAIL_ALREADY_IGNORED)
                } else {
                    val before = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                    graph.addFriend(characterId, targetCharacterId)
                    persistence.enqueueFriendAdd(characterId, targetCharacterId)
                    val after = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                    sendPresenceVisibilityChanges(before, after)
                    pushFriendEntryToOwner(characterId, targetCharacterId)
                    SocialReply.Ok(SocialPackets.encodeSocialOk())
                }
            }
            SocialNameAction.FriendDel -> {
                val before = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                graph.deleteFriend(characterId, targetCharacterId)
                persistence.enqueueFriendDelete(characterId, targetCharacterId)
                val after = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                sendPresenceVisibilityChanges(before, after)
                SocialReply.Ok(SocialPackets.encodeSocialOk())
            }
            SocialNameAction.IgnoreAdd -> {
                if (graph.isIgnored(characterId, targetCharacterId)) {
                    socialFail(WorldOpcodes.SOCIAL_FAIL_ALREADY_IGNORED)
                } else if (graph.isFriend(characterId, targetCharacterId)) {
                    socialFail(WorldOpcodes.SOCIAL_FAIL_ALREADY_FRIEND)
                } else {
                    val before = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                    graph.addIgnore(characterId, targetCharacterId)
                    persistence.enqueueIgnoreAdd(characterId, targetCharacterId)
                    val after = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                    sendPresenceVisibilityChanges(before, after)
                    SocialReply.Ok(SocialPackets.encodeSocialOk())
                }
            }
            SocialNameAction.IgnoreDel -> {
                val before = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                graph.deleteIgnore(characterId, targetCharacterId)
                persistence.enqueueIgnoreDelete(characterId, targetCharacterId)
                val after = graph.visiblePresenceRecipients(characterId, friendWorld, onlineIndex)
                sendPresenceVisibilityChanges(before, after)
                SocialReply.Ok(SocialPackets.encodeSocialOk())
            }
        }
    }

    private fun resolveCharacterId(name: String): Int? {
        graph.findCharacterByDisplayName(name)?.let { return it }
        val row = repository.findCharacterByDisplayName(name) ?: return null
        graph.putCharacterName(row.characterId, row.displayName, null)
        return row.characterId
    }

    private fun authorizeWorldSocial(
        worldId: Int,
        characterId: Int,
    ): Boolean {
        if (characterId <= 0) {
            log.warn("World social denied: non-positive characterId={}", characterId)
            return false
        }
        if (!onlineIndex.isOnline(characterId, worldId)) {
            log.warn(
                "World social denied: characterId={} not online on worldId={}",
                characterId,
                worldId,
            )
            return false
        }
        return true
    }

    private fun fanoutFriendPresence(
        friendCharacterId: Int,
        onlineWorldId: Int,
    ) {
        val recipients = graph.friendPresenceRecipients(friendCharacterId, onlineWorldId, onlineIndex)
        for (recipient in recipients) {
            sendFriendPresence(recipient)
        }
    }

    private fun sendFriendPresence(recipient: FriendPresenceRecipient) {
        val frame =
            SocialPackets.encodeServerFriendPresence(
                FriendPresencePush(
                    ownerCharacterId = recipient.ownerCharacterId,
                    friendCharacterId = recipient.friendCharacterId,
                    friendWorldId = recipient.visibleWorldId,
                    friendDisplayName = recipient.friendDisplayName,
                    friendPreviousDisplayName = recipient.friendPreviousDisplayName,
                ),
            )
        registry.push(recipient.ownerWorldId, frame)
    }

    private fun pushFriendEntryToOwner(
        ownerCharacterId: Int,
        friendCharacterId: Int,
    ) {
        val ownerWorld = onlineIndex.worldFor(ownerCharacterId) ?: return
        val profile = graph.profile(friendCharacterId) ?: return
        val friendWorld = onlineIndex.worldFor(friendCharacterId) ?: 0
        val visible = graph.visibleFriendWorld(ownerCharacterId, friendCharacterId, friendWorld)
        if (visible <= 0) {
            return
        }
        sendFriendPresence(
            FriendPresenceRecipient(
                ownerCharacterId = ownerCharacterId,
                ownerWorldId = ownerWorld,
                friendCharacterId = friendCharacterId,
                friendDisplayName = profile.displayName,
                friendPreviousDisplayName = profile.previousDisplayName,
                visibleWorldId = visible,
            ),
        )
    }

    private fun sendPresenceVisibilityChanges(
        beforeVisible: Map<Int, FriendPresenceRecipient>,
        afterVisible: Map<Int, FriendPresenceRecipient>,
    ) {
        for ((ownerCharacterId, before) in beforeVisible) {
            if (ownerCharacterId !in afterVisible) {
                sendFriendPresence(before.copy(visibleWorldId = 0))
            }
        }
        for ((ownerCharacterId, after) in afterVisible) {
            if (ownerCharacterId !in beforeVisible) {
                sendFriendPresence(after)
            }
        }
    }

    fun pruneStalePresenceForWorld(worldId: Int) {
        if (worldId <= 0) {
            return
        }
        val activeIds = presence.activeCharacterIdsForWorld(worldId)
        for (entry in onlineIndex.allOnline()) {
            if (entry.worldId == worldId && entry.characterId !in activeIds) {
                onCharacterOffline(worldId, entry.characterId)
            }
        }
    }

    fun pruneStalePresence() {
        val activeIds = presence.activeCharacterIds()
        for (entry in onlineIndex.allOnline()) {
            if (entry.characterId !in activeIds) {
                onCharacterOffline(entry.worldId, entry.characterId)
            }
        }
    }

    private fun friendWorldForSnapshot(characterId: Int): Int =
        presence.worldForFriendSnapshot(characterId, ::onCharacterOffline)

    private fun socialFail(reason: Int = WorldOpcodes.SOCIAL_FAIL_NOT_ALLOWED): SocialReply.Fail =
        SocialReply.Fail(SocialPackets.encodeSocialFail(reason))

    private fun socialSyncFail(reason: Int = WorldOpcodes.SOCIAL_FAIL_NOT_ALLOWED): SocialReply.Fail =
        SocialReply.Fail(SocialPackets.encodeSocialSyncFail(reason))

    sealed class SocialReply {
        data class Ok(val frame: ByteArray) : SocialReply()

        data class Fail(val frame: ByteArray) : SocialReply()
    }
}
