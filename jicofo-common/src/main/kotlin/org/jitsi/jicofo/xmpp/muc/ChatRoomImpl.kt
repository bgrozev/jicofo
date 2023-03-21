/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.xmpp.muc

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.jicofo.TaskPools.Companion.ioPool
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.muc.MemberRole.Companion.fromSmack
import org.jitsi.jicofo.xmpp.sendIqAndGetResponse
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.observableWhenChanged
import org.jivesoftware.smack.PresenceListener
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.PresenceBuilder
import org.jivesoftware.smack.util.Consumer
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.Occupant
import org.jivesoftware.smackx.muc.ParticipantStatusListener
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.muc.packet.MUCAdmin
import org.jivesoftware.smackx.muc.packet.MUCInitialPresence
import org.jivesoftware.smackx.muc.packet.MUCItem
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.ConcurrentHashMap

@SuppressFBWarnings(
    value = ["JLM_JSR166_UTILCONCURRENT_MONITORENTER"],
    justification = "We intentionally synchronize on [members] (a ConcurrentHashMap)."
)
class ChatRoomImpl(
    override val xmppProvider: XmppProvider,
    override val roomJid: EntityBareJid,
    /** Callback to call when the room is left. */
    private val leaveCallback: (ChatRoomImpl) -> Unit
) : ChatRoom, PresenceListener {
    private val logger = createLogger().apply {
        addContext("room", roomJid.toString())
    }

    private val memberListener: MemberListener = MemberListener()

    private val userListener = LocalUserStatusListener()

    /** Listener for presence that smack sends on our behalf. */
    private var presenceInterceptor = Consumer<PresenceBuilder> { presenceBuilder ->
        // The initial presence sent by smack contains an empty "x"
        // extension. If this extension is included in a subsequent stanza,
        // it indicates that the client lost its synchronization and causes
        // the MUC service to re-send the presence of each occupant in the
        // room.
        synchronized(this@ChatRoomImpl) {
            val p = presenceBuilder.build()
            p.removeExtension(
                MUCInitialPresence.ELEMENT,
                MUCInitialPresence.NAMESPACE
            )
            lastPresenceSent = p.asBuilder()
        }
    }

    /** Smack multi user chat backend instance. */
    private val muc: MultiUserChat =
        MultiUserChatManager.getInstanceFor(xmppProvider.xmppConnection).getMultiUserChat(this.roomJid).apply {
            addParticipantStatusListener(memberListener)
            addUserStatusListener(userListener)
            addParticipantListener(this@ChatRoomImpl)
        }

    /** Our full Multi User Chat XMPP address. */
    private var myOccupantJid: EntityFullJid? = null

    private val membersMap: MutableMap<EntityFullJid, ChatRoomMemberImpl> = ConcurrentHashMap()

    /** Stores our last MUC presence packet for future update. */
    private var lastPresenceSent: PresenceBuilder? = null

    /** The value of the "meetingId" field from the MUC form, if present. */
    override var meetingId: String? = null
        private set

    /** The value of the "isbreakout" field from the MUC form, if present. */
    override var isBreakoutRoom = false
        private set

    /** The value of "breakout_main_room" field from the MUC form, if present. */
    override var mainRoom: String? = null
        private set

    private val avModerationByMediaType = mutableMapOf<MediaType, AvModerationForMediaType>()
    /** In practice we only use AUDIO and VIDEO, so polluting the map is not a problem. */
    private fun avModeration(mediaType: MediaType): AvModerationForMediaType =
        avModerationByMediaType.computeIfAbsent(mediaType) { AvModerationForMediaType(mediaType) }

    /** The emitter used to fire events. */
    private val eventEmitter: EventEmitter<ChatRoomListener> = SyncEventEmitter()

    private object MucConfigFields {
        const val IS_BREAKOUT_ROOM = "muc#roominfo_isbreakout"
        const val MAIN_ROOM = "muc#roominfo_breakout_main_room"
        const val MEETING_ID = "muc#roominfo_meetingId"
        const val WHOIS = "muc#roomconfig_whois"
    }

    override fun addListener(listener: ChatRoomListener) = eventEmitter.addHandler(listener)
    override fun removeListener(listener: ChatRoomListener) = eventEmitter.removeHandler(listener)
    override val isJoined
        get() = muc.isJoined
    override val members: List<ChatRoomMember>
        get() = synchronized(membersMap) { return membersMap.values.toList() }
    override val memberCount
        get() = membersMap.size
    override fun getChatMember(occupantJid: EntityFullJid) = membersMap[occupantJid]
    override fun isAvModerationEnabled(mediaType: MediaType) = avModeration(mediaType).enabled
    override fun setAvModerationEnabled(mediaType: MediaType, value: Boolean) {
        avModeration(mediaType).enabled = value
    }
    override fun setAvModerationWhitelist(mediaType: MediaType, whitelist: List<String>) {
        avModeration(mediaType).whitelist = whitelist
    }
    override fun isMemberAllowedToUnmute(jid: Jid, mediaType: MediaType): Boolean =
        avModeration(mediaType).isAllowedToUnmute(jid)

    // Use toList to avoid concurrent modification. TODO: add a removeAll to EventEmitter.
    override fun removeAllListeners() = eventEmitter.eventHandlers.toList().forEach { eventEmitter.removeHandler(it) }

    fun setStartMuted(startAudioMuted: Boolean, startVideoMuted: Boolean) = eventEmitter.fireEvent {
        startMutedChanged(startAudioMuted, startVideoMuted)
    }

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    override fun join() {
        // TODO: clean-up the way we figure out what nickname to use.
        resetState()
        joinAs(xmppProvider.config.username)
    }

    /**
     * Prepare this [ChatRoomImpl] for a call to [.joinAs], which send initial presence to
     * the MUC. Resets any state that might have been set the previous time the MUC was joined.
     */
    private fun resetState() {
        synchronized(membersMap) {
            if (membersMap.isNotEmpty()) {
                logger.warn("Removing ${membersMap.size} stale members.")
                membersMap.clear()
            }
        }
        synchronized(this) {
            lastPresenceSent = null
            meetingId = null
            logger.addContext("meeting_id", "")
            isBreakoutRoom = false
            mainRoom = null
            avModerationByMediaType.values.forEach { it.reset() }
        }
    }

    @Throws(SmackException::class, XMPPException::class, InterruptedException::class)
    private fun joinAs(nickname: Resourcepart) {
        myOccupantJid = JidCreate.entityFullFrom(roomJid, nickname)
        if (muc.isJoined) {
            muc.leave()
        }
        muc.addPresenceInterceptor(presenceInterceptor)
        muc.createOrJoin(nickname)
        val config = muc.configurationForm

        // Read breakout rooms options
        val isBreakoutRoomField = config.getField(MucConfigFields.IS_BREAKOUT_ROOM)
        if (isBreakoutRoomField != null) {
            isBreakoutRoom = isBreakoutRoomField.firstValue.toBoolean()
            if (isBreakoutRoom) {
                mainRoom = config.getField(MucConfigFields.MAIN_ROOM)?.firstValue
            }
        }

        // Read meetingId
        val meetingIdField = config.getField(MucConfigFields.MEETING_ID)
        if (meetingIdField != null) {
            meetingId = meetingIdField.firstValue
            if (meetingId != null) {
                logger.addContext("meeting_id", meetingId)
            }
        }

        // Make the room non-anonymous, so that others can recognize focus JID
        val answer = config.fillableForm
        answer.setAnswer(MucConfigFields.WHOIS, "anyone")
        muc.sendConfigurationForm(answer)
    }

    override fun leave() {
        muc.removePresenceInterceptor(presenceInterceptor)
        muc.removeParticipantStatusListener(memberListener)
        muc.removeUserStatusListener(userListener)
        muc.removeParticipantListener(this)
        leaveCallback(this)

        // Call MultiUserChat.leave() in an IO thread, because it now (with Smack 4.4.3) blocks waiting for a response
        // from the XMPP server (and we want ChatRoom#leave to return immediately).
        ioPool.execute {
            val connection: XMPPConnection = xmppProvider.xmppConnection
            try {
                // FIXME smack4: there used to be a custom dispose() method if leave() fails, there might still be some
                // listeners lingering around
                if (isJoined) {
                    muc.leave()
                }
            } catch (e: Exception) {
                // when the connection is not connected or we get NotConnectedException, this is expected (skip log)
                if (connection.isConnected || e !is SmackException.NotConnectedException) {
                    logger.error("Failed to properly leave $muc", e)
                }
            }
        }
    }

    override fun grantOwnership(member: ChatRoomMember) {
        logger.debug("Grant owner to $member")

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        val jid = member.jid?.asBareJid() ?: run {
            logger.warn("Can not grant ownership to ${member.name}, real JID unknown")
            return
        }
        val item = MUCItem(MUCAffiliation.owner, jid)

        val admin = MUCAdmin().apply {
            type = IQ.Type.set
            to = roomJid
            addItem(item)
        }
        try {
            val reply = xmppProvider.xmppConnection.sendIqAndGetResponse(admin)
            if (reply == null || reply.type != IQ.Type.result) {
                logger.warn("Failed to grant ownership: ${reply?.toXML() ?: "timeout"}")
            }
        } catch (e: SmackException.NotConnectedException) {
            logger.warn("Failed to grant ownership: XMPP disconnected")
        }
    }

    fun getOccupant(chatMember: ChatRoomMemberImpl): Occupant? = muc.getOccupant(chatMember.occupantJid)

    override fun setPresenceExtension(extension: ExtensionElement) {
        val presenceToSend = synchronized(this) {
            lastPresenceSent?.let { presence ->
                presence.getExtensions(extension.qName).toList().forEach { existingExtension ->
                    presence.removeExtension(existingExtension)
                }
                presence.addExtension(extension)

                presence.build()
            } ?: run {
                logger.error("No presence packet obtained yet")
                null
            }
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun addPresenceExtensionIfMissing(extension: ExtensionElement) {
        val presenceToSend = synchronized(this) {
            lastPresenceSent?.let { presence ->
                if (presence.extensions?.any { it.qName == extension.qName } == true) {
                    null
                } else {
                    presence.addExtension(extension).build()
                }
            }
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun removePresenceExtensions(pred: (ExtensionElement) -> Boolean) {
        val presenceToSend = synchronized(this) {
            lastPresenceSent?.extensions?.filter { pred(it) }?.let {
                modifyPresenceExtensions(toRemove = it)
            }
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    override fun addPresenceExtensions(extensions: Collection<ExtensionElement>) {
        val presenceToSend = synchronized(this) {
            modifyPresenceExtensions(toAdd = extensions)
        }
        presenceToSend?.let { xmppProvider.xmppConnection.tryToSendStanza(it) }
    }

    /** Add/remove extensions to/from our presence and return the updated presence or null if it wasn't modified. */
    private fun modifyPresenceExtensions(
        toRemove: Collection<ExtensionElement> = emptyList(),
        toAdd: Collection<ExtensionElement> = emptyList()
    ): Presence? = synchronized(this) {
        lastPresenceSent?.let { presence ->
            var changed = false
            toRemove.forEach {
                presence.removeExtension(it)
                // We don't have a good way to check if it was actually removed.
                changed = true
            }
            toAdd.forEach {
                presence.addExtension(it)
                changed = true
            }
            if (changed) presence.build() else null
        } ?: run {
            logger.error("No presence packet obtained yet")
            null
        }
    }

    /** The number of members that currently have their audio sources unmuted. */
    override var audioSendersCount by observableWhenChanged(0) { _, _, newValue ->
        logger.debug { "The number of audio senders has changed to $newValue." }
        eventEmitter.fireEvent { numAudioSendersChanged(newValue) }
    }

    /** The number of members that currently have their video sources unmuted. */
    override var videoSendersCount by observableWhenChanged(0) { _, _, newValue ->
        logger.debug { "The number of video senders has changed to $newValue." }
        eventEmitter.fireEvent { numVideoSendersChanged(newValue) }
    }

    /**
     * Adds a new [ChatRoomMemberImpl] with the given JID to [.members].
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the [ChatRoomMemberImpl] for the member with the given JID.
     */
    private fun addMember(jid: EntityFullJid): ChatRoomMemberImpl {
        synchronized(membersMap) {
            membersMap[jid]?.let { return it }
            val newMember = ChatRoomMemberImpl(jid, this@ChatRoomImpl, logger)
            membersMap[jid] = newMember
            if (!newMember.isAudioMuted) audioSendersCount++
            if (!newMember.isVideoMuted) videoSendersCount++
            return newMember
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or `null`.
     */
    fun getJid(occupantJid: EntityFullJid): Jid? = muc.getOccupant(occupantJid)?.jid ?: run {
        logger.error("Unable to get occupant for $occupantJid")
        null
    }

    /**
     * Processes a <tt>Presence</tt> packet addressed to our own occupant
     * JID.
     * @param presence the packet to process.
     */
    private fun processOwnPresence(presence: Presence) {
        val mucUser = presence.getExtension(MUCUser::class.java)
        if (mucUser != null) {
            val affiliation = mucUser.item.affiliation
            val role = mucUser.item.role

            // This is our initial role and affiliation, as smack does not fire any initial events.
            if (!presence.isAvailable && MUCAffiliation.none == affiliation && MUCRole.none == role) {
                val destroy = mucUser.destroy
                if (destroy == null) {
                    // the room is unavailable to us, there is no
                    // message we will just leave
                    leave()
                } else {
                    logger.info("Leave, reason: ${destroy.reason} alt-jid: ${destroy.jid}")
                    leave()
                }
            } else {
                eventEmitter.fireEvent { localRoleChanged(fromSmack(role, affiliation)) }
            }
        }
    }

    /**
     * Process a <tt>Presence</tt> packet sent by one of the other room
     * occupants.
     * @param presence the presence.
     */
    private fun processOtherPresence(presence: Presence) {
        val jid = presence.from.asEntityFullJidIfPossible() ?: run {
            logger.warn("Presence without a valid jid: ${presence.from}")
            return
        }

        var memberJoined = false
        var memberLeft = false
        val member = synchronized(membersMap) {
            val m = getChatMember(jid)
            if (m == null) {
                if (presence.type == Presence.Type.available) {
                    // This is how we detect that a new member has joined. We do not use the
                    // ParticipantStatusListener#joined callback.
                    logger.debug { "Joined $jid room: $roomJid" }
                    memberJoined = true
                    addMember(jid)
                } else {
                    // We received presence from an unknown member which doesn't look like a new member's presence.
                    // Ignore it. The member might have been just removed via left(), which is fine.
                    null
                }
            } else {
                memberLeft = presence.type == Presence.Type.unavailable
                m
            }
        }
        if (member != null) {
            member.processPresence(presence)
            if (memberJoined) {
                // Trigger member "joined"
                eventEmitter.fireEvent { memberJoined(member) }
            } else if (memberLeft) {
                // In some cases smack fails to call left(). We'll call it here
                // any time we receive presence unavailable
                memberListener.left(jid)
            }
            if (!memberLeft) {
                eventEmitter.fireEvent { memberPresenceChanged(member) }
            }
        }
    }

    /**
     * Processes an incoming presence packet.
     *
     * @param presence the incoming presence.
     */
    override fun processPresence(presence: Presence?) {
        if (presence == null || presence.error != null) {
            logger.warn("Unable to handle packet: ${presence?.toXML()}")
            return
        }
        logger.trace { "Presence received ${presence.toXML()}" }

        // Should never happen, but log if something is broken
        val myOccupantJid = this.myOccupantJid
        if (myOccupantJid == null) {
            logger.error("Processing presence when myOccupantJid is not set: ${presence.toXML()}")
        }
        if (myOccupantJid != null && myOccupantJid.equals(presence.from)) {
            processOwnPresence(presence)
        } else {
            processOtherPresence(presence)
        }
    }

    override val debugState = OrderedJsonObject().apply {
        this["room_jid"] = roomJid.toString()
        this["my_occupant_jid"] = myOccupantJid.toString()
        val membersJson = OrderedJsonObject()
        membersMap.values.forEach {
            membersJson[it.name] = it.debugState
        }
        this["members"] = membersJson
        this["meeting_id"] = meetingId.toString()
        this["is_breakout_room"] = isBreakoutRoom
        this["main_room"] = mainRoom.toString()
        this["audio_senders_count"] = audioSendersCount
        this["video_senders_count"] = videoSendersCount
        this["av_moderation"] = OrderedJsonObject().apply {
            avModerationByMediaType.forEach { (k, v) -> this[k.toString()] = v.debugState }
        }
    }

    internal inner class MemberListener : ParticipantStatusListener {
        override fun joined(mucJid: EntityFullJid) {
            // When a new member joins, Smack seems to fire ParticipantStatusListener#joined and
            // PresenceListener#processPresence in a non-deterministic order.

            // In order to ensure that we have all the information contained in presence at the time that we create a
            // new ChatMemberImpl, we completely ignore this joined event. Instead, we rely on processPresence to detect
            // when a new member has joined and trigger the creation of a ChatMemberImpl by calling
            // ChatRoomImpl#memberJoined()
            logger.debug { "Ignore a member joined event for $mucJid" }
        }

        private fun removeMember(occupantJid: EntityFullJid): ChatRoomMemberImpl? {
            synchronized(membersMap) {
                val removed = membersMap.remove(occupantJid)
                if (removed == null) {
                    logger.error("$occupantJid not in room")
                } else {
                    if (!removed.isAudioMuted) audioSendersCount--
                    if (!removed.isVideoMuted) videoSendersCount--
                }
                return removed
            }
        }

        /**
         * This needs to be prepared to run twice for the same member.
         */
        override fun left(occupantJid: EntityFullJid) {
            logger.debug { "Left $occupantJid room: $roomJid" }

            val member = synchronized(membersMap) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberLeft(it) } }
                ?: logger.info("Member left event for non-existing member: $occupantJid")
        }

        override fun kicked(occupantJid: EntityFullJid, actor: Jid, reason: String) {
            logger.debug { "Kicked: $occupantJid, $actor, $reason" }

            val member = synchronized(membersMap) { removeMember(occupantJid) }
            member?.let { eventEmitter.fireEvent { memberKicked(it) } }
                ?: logger.error("Kicked member does not exist: $occupantJid")
        }

        override fun nicknameChanged(oldNickname: EntityFullJid, newNickname: Resourcepart) {
            logger.error("nicknameChanged - NOT IMPLEMENTED")
        }
    }

    /**
     * Listens for room destroyed and pass it to the conference.
     */
    private inner class LocalUserStatusListener : UserStatusListener {
        override fun roomDestroyed(alternateMUC: MultiUserChat, reason: String) {
            eventEmitter.fireEvent { roomDestroyed(reason) }
        }
    }

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    private inner class AvModerationForMediaType(mediaType: MediaType) {
        var enabled: Boolean by observableWhenChanged(false) { _, _, newValue ->
            logger.info("Setting enabled=$newValue for $mediaType")
        }
        var whitelist: List<String> by observableWhenChanged(emptyList()) { _, _, newValue ->
            logger.info("Setting whitelist for $mediaType: $newValue")
        }

        fun isAllowedToUnmute(jid: Jid) = !enabled || whitelist.contains(jid.toString())

        fun reset() {
            enabled = false
            whitelist = emptyList()
        }

        val debugState: OrderedJsonObject
            get() = OrderedJsonObject().apply {
                this["enabled"] = enabled
                this["whitelist"] = whitelist
            }
    }
}
