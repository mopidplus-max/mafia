package com.danya.mafia.game

import org.json.JSONArray
import org.json.JSONObject

data class RoomConfig(
    val name: String,
    val password: String? = null,
    val allowedRoles: Set<String> = Role.defaultAllowed,
    val maxPlayers: Int = 0,
    val autoHost: Boolean = true
)

data class RoomInfo(
    val name: String,
    val hasPassword: Boolean,
    val playerCount: Int,
    val maxPlayers: Int,
    val status: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("hasPassword", hasPassword)
        put("playerCount", playerCount)
        put("maxPlayers", maxPlayers)
        put("status", status)
    }

    companion object {
        fun fromJson(o: JSONObject) = RoomInfo(
            name = o.optString("name"),
            hasPassword = o.optBoolean("hasPassword"),
            playerCount = o.optInt("playerCount"),
            maxPlayers = o.optInt("maxPlayers"),
            status = o.optString("status", "lobby")
        )
    }
}

data class PlayerView(
    val id: String,
    val name: String,
    val device: String,
    val alive: Boolean,
    val roleId: String? = null,
    val roleTitle: String? = null,
    val roleIcon: String? = null,
    val roleDescription: String? = null,
    val roleTeam: String? = null,
    val isYou: Boolean = false,
    val left: Boolean = false,
    val guardian: Boolean = false,
    val cat: Boolean = false,
    val catBroken: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("device", device)
        put("alive", alive)
        put("isYou", isYou)
        put("left", left)
        put("guardian", guardian)
        put("cat", cat)
        put("catBroken", catBroken)
        if (roleId != null) put("roleId", roleId)
        if (roleTitle != null) put("roleTitle", roleTitle)
        if (roleIcon != null) put("roleIcon", roleIcon)
        if (roleDescription != null) put("roleDescription", roleDescription)
        if (roleTeam != null) put("roleTeam", roleTeam)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlayerView(
            id = o.optString("id"),
            name = o.optString("name"),
            device = o.optString("device", "android"),
            alive = o.optBoolean("alive", true),
            roleId = o.nullableString("roleId"),
            roleTitle = o.nullableString("roleTitle"),
            roleIcon = o.nullableString("roleIcon"),
            roleDescription = o.nullableString("roleDescription"),
            roleTeam = o.nullableString("roleTeam"),
            isYou = o.optBoolean("isYou"),
            left = o.optBoolean("left"),
            guardian = o.optBoolean("guardian"),
            cat = o.optBoolean("cat"),
            catBroken = o.optBoolean("catBroken")
        )
    }
}

/** Звук, который ведущий включает на телефонах игроков. */
data class SoundCommand(
    val seq: Long,
    val kind: String,
    val targetId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("kind", kind)
        if (targetId != null) put("targetId", targetId)
    }

    companion object {
        fun fromJson(o: JSONObject) = SoundCommand(
            seq = o.optLong("seq"),
            kind = o.optString("kind"),
            targetId = o.nullableString("targetId")
        )
    }
}

/** Котость спасла игрока — на всех экранах показываем анимацию. */
data class CatEvent(
    val seq: Long,
    val playerId: String,
    val playerName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("playerId", playerId)
        put("playerName", playerName)
    }

    companion object {
        fun fromJson(o: JSONObject) = CatEvent(
            seq = o.optLong("seq"),
            playerId = o.optString("playerId"),
            playerName = o.optString("playerName")
        )
    }
}

data class TargetView(
    val id: String,
    val name: String,
    val device: String,
    val alive: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("device", device)
        put("alive", alive)
    }

    companion object {
        fun fromJson(o: JSONObject) = TargetView(
            id = o.optString("id"),
            name = o.optString("name"),
            device = o.optString("device", "android"),
            alive = o.optBoolean("alive", true)
        )
    }
}

data class ActionPrompt(
    val kind: String,
    val title: String,
    val targets: List<TargetView>,
    val canSkip: Boolean,
    val minTargets: Int = 1,
    val maxTargets: Int = 1,
    val endsAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("title", title)
        put("canSkip", canSkip)
        put("minTargets", minTargets)
        put("maxTargets", maxTargets)
        put("endsAt", endsAt)
        put("targets", JSONArray().apply { targets.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ActionPrompt {
            val targets = mutableListOf<TargetView>()
            val arr = o.optJSONArray("targets") ?: JSONArray()
            for (i in 0 until arr.length()) targets.add(TargetView.fromJson(arr.getJSONObject(i)))
            return ActionPrompt(
                kind = o.optString("kind"),
                title = o.optString("title"),
                targets = targets,
                canSkip = o.optBoolean("canSkip", true),
                minTargets = o.optInt("minTargets", 1),
                maxTargets = o.optInt("maxTargets", 1),
                endsAt = o.optLong("endsAt")
            )
        }
    }
}

data class VoteStatus(
    val voterId: String?,
    val voterName: String?,
    val endsAt: Long,
    val done: Int,
    val total: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (voterId != null) put("voterId", voterId)
        if (voterName != null) put("voterName", voterName)
        put("endsAt", endsAt)
        put("done", done)
        put("total", total)
    }

    companion object {
        fun fromJson(o: JSONObject) = VoteStatus(
            voterId = o.nullableString("voterId"),
            voterName = o.nullableString("voterName"),
            endsAt = o.optLong("endsAt"),
            done = o.optInt("done"),
            total = o.optInt("total")
        )
    }
}

data class ChatMessage(
    val id: Long,
    val authorId: String,
    val authorName: String,
    val text: String,
    val at: Long,
    val dead: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("authorId", authorId)
        put("authorName", authorName)
        put("text", text)
        put("at", at)
        put("dead", dead)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(
            id = o.optLong("id"),
            authorId = o.optString("authorId"),
            authorName = o.optString("authorName"),
            text = o.optString("text"),
            at = o.optLong("at"),
            dead = o.optBoolean("dead")
        )
    }
}

/** Что сейчас делает игрок на сайте — для экрана ведущего. */
data class PlayerActivity(
    val playerId: String,
    val playerName: String,
    val screen: String,
    val detail: String,
    val scroll: Int,
    val at: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("playerId", playerId)
        put("playerName", playerName)
        put("screen", screen)
        put("detail", detail)
        put("scroll", scroll)
        put("at", at)
    }
}

data class ClientState(
    val roomName: String,
    val phase: String,
    val phaseLabel: String,
    val you: PlayerView,
    val players: List<PlayerView>,
    val playerCount: Int,
    val maxPlayers: Int,
    val hasPassword: Boolean,
    val serverNow: Long,
    val startAt: Long? = null,
    val phaseEndsAt: Long? = null,
    val prompt: ActionPrompt? = null,
    val message: String = "",
    val privateMessage: String? = null,
    val events: List<String> = emptyList(),
    val vote: VoteStatus? = null,
    val winner: String? = null,
    val rolesRevealed: Boolean = false,
    val nightRoleTitle: String? = null,
    val nightRoleIcon: String? = null,
    val chat: List<ChatMessage> = emptyList(),
    val canChat: Boolean = false,
    val sound: SoundCommand? = null,
    val catSave: CatEvent? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("roomName", roomName)
        put("phase", phase)
        put("phaseLabel", phaseLabel)
        put("you", you.toJson())
        put("players", JSONArray().apply { players.forEach { put(it.toJson()) } })
        put("playerCount", playerCount)
        put("maxPlayers", maxPlayers)
        put("hasPassword", hasPassword)
        put("serverNow", serverNow)
        put("message", message)
        put("rolesRevealed", rolesRevealed)
        put("canChat", canChat)
        put("chat", JSONArray().apply { chat.forEach { put(it.toJson()) } })
        if (sound != null) put("sound", sound.toJson())
        if (catSave != null) put("catSave", catSave.toJson())
        if (startAt != null) put("startAt", startAt)
        if (phaseEndsAt != null) put("phaseEndsAt", phaseEndsAt)
        if (prompt != null) put("prompt", prompt.toJson())
        if (privateMessage != null) put("privateMessage", privateMessage)
        put("events", JSONArray().apply { events.forEach { put(it) } })
        if (vote != null) put("vote", vote.toJson())
        if (winner != null) put("winner", winner)
        if (nightRoleTitle != null) put("nightRoleTitle", nightRoleTitle)
        if (nightRoleIcon != null) put("nightRoleIcon", nightRoleIcon)
    }

    companion object {
        fun fromJson(o: JSONObject): ClientState {
            val players = mutableListOf<PlayerView>()
            val pArr = o.optJSONArray("players") ?: JSONArray()
            for (i in 0 until pArr.length()) players.add(PlayerView.fromJson(pArr.getJSONObject(i)))
            val events = mutableListOf<String>()
            val eArr = o.optJSONArray("events") ?: JSONArray()
            for (i in 0 until eArr.length()) events.add(eArr.getString(i))
            val chat = mutableListOf<ChatMessage>()
            val cArr = o.optJSONArray("chat") ?: JSONArray()
            for (i in 0 until cArr.length()) chat.add(ChatMessage.fromJson(cArr.getJSONObject(i)))
            return ClientState(
                roomName = o.optString("roomName"),
                phase = o.optString("phase", "lobby"),
                phaseLabel = o.optString("phaseLabel", "Лобби"),
                you = PlayerView.fromJson(o.optJSONObject("you") ?: JSONObject()),
                players = players,
                playerCount = o.optInt("playerCount"),
                maxPlayers = o.optInt("maxPlayers"),
                hasPassword = o.optBoolean("hasPassword"),
                serverNow = o.optLong("serverNow", System.currentTimeMillis()),
                startAt = o.nullableLong("startAt"),
                phaseEndsAt = o.nullableLong("phaseEndsAt"),
                prompt = o.optJSONObject("prompt")?.let(ActionPrompt::fromJson),
                message = o.optString("message"),
                privateMessage = o.nullableString("privateMessage"),
                events = events,
                vote = o.optJSONObject("vote")?.let(VoteStatus::fromJson),
                winner = o.nullableString("winner"),
                rolesRevealed = o.optBoolean("rolesRevealed"),
                nightRoleTitle = o.nullableString("nightRoleTitle"),
                nightRoleIcon = o.nullableString("nightRoleIcon"),
                chat = chat,
                canChat = o.optBoolean("canChat"),
                sound = o.optJSONObject("sound")?.let(SoundCommand::fromJson),
                catSave = o.optJSONObject("catSave")?.let(CatEvent::fromJson)
            )
        }
    }
}

data class HostSnapshot(
    val roomName: String,
    val phase: String,
    val phaseLabel: String,
    val nightNum: Int,
    val dayNum: Int,
    val players: List<PlayerView>,
    val maxPlayers: Int,
    val hasPassword: Boolean,
    val serverNow: Long,
    val startAt: Long? = null,
    val phaseEndsAt: Long? = null,
    val events: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val nightTitle: String? = null,
    val nightBody: String? = null,
    val actionProgress: String? = null,
    val canAdvance: Boolean = false,
    val advanceLabel: String = "Далее",
    val winner: String? = null,
    val vote: VoteStatus? = null,
    val canStart: Boolean = false,
    val canRestart: Boolean = false,
    val allowedRoles: List<String> = emptyList(),
    val minPlayers: Int = 4,
    val autoHost: Boolean = false,
    val chat: List<ChatMessage> = emptyList(),
    val activity: List<PlayerActivity> = emptyList(),
    val pistolHolder: String? = null,
    val guardianNames: List<String> = emptyList()
)

data class JoinResponse(
    val ok: Boolean,
    val playerId: String? = null,
    val token: String? = null,
    val room: RoomInfo? = null,
    val error: String? = null
)

internal fun JSONObject.nullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.nullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
