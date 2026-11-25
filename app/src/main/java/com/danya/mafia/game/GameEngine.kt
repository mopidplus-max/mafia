package com.danya.mafia.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

data class JoinResult(
    val ok: Boolean,
    val playerId: String? = null,
    val token: String? = null,
    val error: String? = null
)

data class ActionResult(val ok: Boolean, val error: String? = null)

private enum class Phase(val id: String) {
    LOBBY("lobby"),
    STARTING("starting"),
    NIGHT_INTRO("night_intro"),
    NIGHT_STEP("night_step"),
    NIGHT_RESULTS("night_results"),
    DAY_DISCUSSION("day_discussion"),
    VOTING("voting"),
    VOTE_RESULTS("vote_results"),
    GAME_OVER("game_over")
}

private enum class StepKind { ACTION, TEAM, INFO }

private data class GamePlayer(
    val id: String,
    val token: String,
    val name: String,
    val device: String,
    val joinedAt: Long,
    var role: Role? = null,
    var alive: Boolean = true,
    var left: Boolean = false
)

private data class NightStep(
    val role: Role,
    val kind: StepKind,
    val actorIds: List<String>,
    val infoIds: Set<String>,
    val blockedIds: Set<String>,
    val targetIds: List<String>,
    val title: String,
    val body: String,
    val promptTitle: String,
    val canSkip: Boolean,
    val minTargets: Int,
    val maxTargets: Int,
    val endsAt: Long,
    val submissions: MutableMap<String, List<String>> = mutableMapOf(),
    var done: Boolean = false,
    var doneAt: Long = 0L
)

/**
 * Вся логика игры. Живёт на телефоне ведущего.
 * UI ведущего читает hostSnapshot, а игроки (Android и веб) — clientState().
 */
class GameEngine(
    val config: RoomConfig,
    private val scope: CoroutineScope
) {
    companion object {
        const val MIN_PLAYERS = 4
        private const val START_DELAY_MS = 10_000L
        private const val NIGHT_ACTION_MS = 45_000L
        private const val INFO_MS = 5_000L
        private const val DISCUSSION_MS = 60_000L
        private const val VOTE_MS = 30_000L
    }

    private val lock = Any()
    private val players = mutableListOf<GamePlayer>()

    private var phase = Phase.LOBBY
    private var startAt: Long? = null
    private var phaseEndsAt: Long? = null
    private var nightNum = 1
    private var dayNum = 1
    private var winner: String? = null
    private var jesterWon = false
    private var rolesRevealed = false

    private var events = mutableListOf<String>()
    private val log = mutableListOf<String>()
    private val privateMessages = mutableMapOf<String, String>()

    // ── Ночь ──
    private val nightQueue = mutableListOf<Role>()
    private var step: NightStep? = null
    private var drugBlocked = mutableSetOf<String>()
    private var beautyBlocked = mutableSetOf<String>()
    private var drugTargetId: String? = null
    private var beautyTargetId: String? = null
    private var doctorTargetId: String? = null
    private var commissionerCheckedId: String? = null
    private var angelReviveId: String? = null
    private var pendingMafiaTargetId: String? = null
    private var pendingManiacTargetId: String? = null
    private var witchEvent: String? = null
    private var witchUsed = false
    private var sniperUsed = false

    // ── Голосование ──
    private val voteOrder = mutableListOf<String>()
    private var voteIndex = 0
    private var voteEndsAt = 0L
    private val voteCounts = mutableMapOf<String, Int>()
    private val voteChoices = mutableMapOf<String, String?>()

    private val _hostSnapshot = MutableStateFlow(emptySnapshot())
    val hostSnapshot: StateFlow<HostSnapshot> = _hostSnapshot.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(400)
                synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (tick(now)) emit(now)
                }
            }
        }
        emit()
    }

    // ─────────────────────────────────────────
    //  Комната
    // ─────────────────────────────────────────

    fun roomInfo(): RoomInfo = synchronized(lock) {
        RoomInfo(
            name = config.name,
            hasPassword = !config.password.isNullOrBlank(),
            playerCount = players.size,
            maxPlayers = config.maxPlayers,
            status = phase.id
        )
    }

    fun join(nameRaw: String, deviceRaw: String, password: String?): JoinResult =
        synchronized(lock) {
            if (phase != Phase.LOBBY) {
                return@synchronized JoinResult(false, error = "Игра уже началась")
            }
            if (!config.password.isNullOrBlank() && password != config.password) {
                return@synchronized JoinResult(
                    false,
                    error = if (password.isNullOrBlank()) "Для входа нужен пароль" else "Неверный пароль"
                )
            }
            val name = nameRaw.trim().replace("\n", " ").take(24)
            if (name.isBlank()) {
                return@synchronized JoinResult(false, error = "Введите имя")
            }
            if (players.any { it.name.equals(name, ignoreCase = true) }) {
                return@synchronized JoinResult(false, error = "Такое имя уже занято")
            }
            if (config.maxPlayers > 0 && players.size >= config.maxPlayers) {
                return@synchronized JoinResult(false, error = "В комнате нет мест")
            }
            val device = when (deviceRaw.lowercase()) {
                "ios" -> "ios"
                "android" -> "android"
                else -> "web"
            }
            val p = GamePlayer(
                id = UUID.randomUUID().toString(),
                token = UUID.randomUUID().toString(),
                name = name,
                device = device,
                joinedAt = System.currentTimeMillis()
            )
            players += p
            events += "➕ ${p.name} присоединился"
            addLog("${p.name} присоединился (${device})")
            val now = System.currentTimeMillis()
            emit(now)
            JoinResult(true, p.id, p.token)
        }

    fun leave(playerId: String, token: String): ActionResult = synchronized(lock) {
        val p = auth(playerId, token)
            ?: return@synchronized ActionResult(false, "Сессия не найдена")
        if (phase != Phase.LOBBY) {
            // Во время игры игрок остаётся в партии, но его ходы пропускаются.
            p.left = true
            events += "➖ ${p.name} отключился"
            addLog("${p.name} отключился от игры")
            val now = System.currentTimeMillis()
            if (phase == Phase.VOTING && currentVoter()?.id == p.id) {
                recordVote(null, now)
            }
            emit(now)
            return@synchronized ActionResult(true)
        }
        players.remove(p)
        events += "➖ ${p.name} вышел"
        addLog("${p.name} вышел")
        val now = System.currentTimeMillis()
        emit(now)
        ActionResult(true)
    }

    fun startGame(): ActionResult = synchronized(lock) {
        if (phase != Phase.LOBBY) return@synchronized ActionResult(false, "Игра уже идёт")
        val ready = players.filter { !it.left }
        if (ready.size < MIN_PLAYERS) {
            return@synchronized ActionResult(false, "Нужно минимум $MIN_PLAYERS игрока")
        }
        players.removeAll { it.left }
        val deck = buildDeck(players.size)
        players.forEachIndexed { index, p ->
            p.role = deck[index]
            p.alive = true
        }
        phase = Phase.STARTING
        startAt = System.currentTimeMillis() + START_DELAY_MS
        phaseEndsAt = startAt
        nightNum = 1
        dayNum = 1
        winner = null
        jesterWon = false
        rolesRevealed = false
        privateMessages.clear()
        witchUsed = false
        sniperUsed = false
        step = null
        events = mutableListOf(
            "🃏 Роли розданы",
            "Игра начинается через 10 секунд..."
        )
        addLog("Игра началась. Игроков: ${players.size}")
        val now = System.currentTimeMillis()
        emit(now)
        ActionResult(true)
    }

    fun advance(): ActionResult = synchronized(lock) {
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.STARTING -> beginNightIntro(now)
            Phase.NIGHT_INTRO -> startNight(now)
            Phase.NIGHT_STEP -> {
                val s = step
                if (s != null && !s.done) {
                    resolveStep(s, now)
                    s.done = true
                    s.doneAt = now
                }
                step = null
                startNextStep(now)
            }
            Phase.NIGHT_RESULTS -> {
                if (winner != null) gameOver() else beginDay(now)
            }
            Phase.DAY_DISCUSSION -> beginVoting(now)
            Phase.VOTING -> recordVote(null, now)
            Phase.VOTE_RESULTS -> {
                if (winner != null) {
                    gameOver()
                } else {
                    nightNum += 1
                    beginNightIntro(now)
                }
            }
            Phase.LOBBY, Phase.GAME_OVER -> Unit
        }
        emit(now)
        ActionResult(true)
    }

    // ─────────────────────────────────────────
    //  Действия игроков
    // ─────────────────────────────────────────

    fun handleAction(
        playerId: String,
        token: String,
        type: String,
        targetId: String?,
        targetIds: List<String>
    ): ActionResult = synchronized(lock) {
        val p = auth(playerId, token)
            ?: return@synchronized ActionResult(false, "Сессия не найдена. Переподключитесь.")
        val now = System.currentTimeMillis()
        when (type) {
            "night" -> {
                val targets = if (targetIds.isNotEmpty()) targetIds else listOfNotNull(targetId)
                submitNight(p, targets, now)
            }
            "vote" -> submitVote(p, targetId, now)
            "sniper" -> submitSniper(p, targetId, now)
            "skip" -> submitSkip(p, now)
            else -> ActionResult(false, "Неизвестное действие")
        }
    }

    private fun submitNight(p: GamePlayer, targets: List<String>, now: Long): ActionResult {
        if (phase != Phase.NIGHT_STEP) return ActionResult(false, "Сейчас не ночь")
        val s = step ?: return ActionResult(false, "Сейчас не ваш ход")
        if (s.kind == StepKind.INFO || s.done) return ActionResult(false, "Ход уже завершён")
        if (p.id !in s.actorIds) return ActionResult(false, "Сейчас не ваш ход")
        if (s.submissions.containsKey(p.id)) return ActionResult(false, "Вы уже сделали выбор")

        val clean = targets.distinct()
        if (clean.isEmpty() && !s.canSkip) return ActionResult(false, "Нужно выбрать цель")
        if (clean.isNotEmpty()) {
            if (clean.size < s.minTargets) return ActionResult(false, "Выберите ещё")
            if (clean.size > s.maxTargets) return ActionResult(false, "Слишком много целей")
            if (clean.any { it !in s.targetIds }) return ActionResult(false, "Неверная цель")
        }

        s.submissions[p.id] = clean
        addLog("${p.name}: ход в роли ${s.role.title}${if (clean.isEmpty()) " (пропуск)" else ""}")

        if (s.actorIds.all { s.submissions.containsKey(it) }) {
            resolveStep(s, now)
            s.done = true
            s.doneAt = now + 1_200L
        }
        emit(now)
        return ActionResult(true)
    }

    private fun submitVote(p: GamePlayer, targetId: String?, now: Long): ActionResult {
        if (phase != Phase.VOTING) return ActionResult(false, "Сейчас не голосование")
        val voter = currentVoter() ?: return ActionResult(false, "Голосование уже закончилось")
        if (voter.id != p.id) return ActionResult(false, "Сейчас голосует другой игрок")
        if (voteChoices.containsKey(voter.id)) return ActionResult(false, "Вы уже проголосовали")

        if (targetId != null) {
            val target = playerById(targetId)
                ?: return ActionResult(false, "Игрок не найден")
            if (!target.alive) return ActionResult(false, "Этот игрок уже выбыл")
            if (target.id == voter.id) return ActionResult(false, "Нельзя голосовать за себя")
        }
        recordVote(targetId, now)
        emit(now)
        return ActionResult(true)
    }

    private fun submitSniper(p: GamePlayer, targetId: String?, now: Long): ActionResult {
        if (phase != Phase.DAY_DISCUSSION) return ActionResult(false, "Сейчас нельзя стрелять")
        val sniper = players.firstOrNull { it.alive && it.role == Role.SNIPER }
            ?: return ActionResult(false, "Снайпер уже выбыл")
        if (sniper.id != p.id) return ActionResult(false, "Вы не снайпер")
        if (sniperUsed) return ActionResult(false, "Выстрел уже использован")
        if (targetId == null) return ActionResult(true)

        val target = playerById(targetId) ?: return ActionResult(false, "Игрок не найден")
        if (!target.alive) return ActionResult(false, "Этот игрок уже выбыл")
        if (target.id == sniper.id) return ActionResult(false, "Нельзя стрелять в себя")

        sniperUsed = true
        if (target.role?.team == Team.MAFIA) {
            target.alive = false
            events += "🎯 Снайпер выстрелил в ${target.name}. Это была мафия! Она погибла."
            addLog("Снайпер убил ${target.name} (${target.role?.title})")
        } else {
            sniper.alive = false
            events += "🎯 Снайпер выстрелил в ${target.name}, но попал в мирного. Снайпер погиб."
            addLog("Снайпер промахнулся в ${target.name} и погиб")
        }
        winner = checkWinner()
        if (winner != null) {
            gameOver()
        }
        emit(now)
        return ActionResult(true)
    }

    private fun submitSkip(p: GamePlayer, now: Long): ActionResult {
        return when (phase) {
            Phase.NIGHT_STEP -> {
                val s = step
                if (s != null && p.id in s.actorIds) submitNight(p, emptyList(), now)
                else ActionResult(false, "Сейчас не ваш ход")
            }
            Phase.VOTING -> {
                if (currentVoter()?.id == p.id) submitVote(p, null, now)
                else ActionResult(false, "Сейчас голосует другой игрок")
            }
            Phase.DAY_DISCUSSION -> ActionResult(true)
            else -> ActionResult(false, "Сейчас нельзя пропустить")
        }
    }

    // ─────────────────────────────────────────
    //  Таймеры
    // ─────────────────────────────────────────

    /** Возвращает true, если состояние изменилось. */
    private fun tick(now: Long): Boolean {
        when (phase) {
            Phase.STARTING -> {
                val at = startAt
                if (at != null && now >= at) {
                    beginNightIntro(now)
                    return true
                }
            }
            Phase.NIGHT_STEP -> {
                val s = step
                if (s == null) {
                    startNextStep(now)
                    return true
                }
                if (s.done) {
                    if (now >= s.doneAt) {
                        step = null
                        startNextStep(now)
                        return true
                    }
                } else if (now >= s.endsAt) {
                    resolveStep(s, now)
                    s.done = true
                    s.doneAt = now
                    return true
                }
            }
            Phase.DAY_DISCUSSION -> {
                val at = phaseEndsAt
                if (at != null && now >= at) {
                    beginVoting(now)
                    return true
                }
            }
            Phase.VOTING -> {
                val voter = currentVoter()
                if (voter != null && voter.left) {
                    recordVote(null, now)
                    return true
                }
                if (now >= voteEndsAt) {
                    recordVote(null, now)
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    // ─────────────────────────────────────────
    //  Фазы
    // ─────────────────────────────────────────

    private fun beginNightIntro(now: Long) {
        if (winner != null) {
            gameOver()
            return
        }
        phase = Phase.NIGHT_INTRO
        startAt = null
        phaseEndsAt = null
        step = null
        privateMessages.clear()
        events = mutableListOf("🌙 Ночь $nightNum", "Город засыпает...")
        addLog("Ночь $nightNum: город засыпает")
    }

    private fun startNight(now: Long) {
        phase = Phase.NIGHT_STEP
        phaseEndsAt = null
        step = null
        drugBlocked.clear()
        beautyBlocked.clear()
        drugTargetId = null
        beautyTargetId = null
        doctorTargetId = null
        commissionerCheckedId = null
        angelReviveId = null
        pendingMafiaTargetId = null
        pendingManiacTargetId = null
        witchEvent = null
        nightQueue.clear()

        if (players.any { it.alive && it.role == Role.DRUGGIE }) nightQueue += Role.DRUGGIE
        if (players.any { it.alive && it.role == Role.BEAUTY }) nightQueue += Role.BEAUTY
        if (players.any { it.alive && it.role?.isMafia == true }) nightQueue += Role.MAFIA
        if (players.any { it.alive && it.role == Role.DOCTOR }) nightQueue += Role.DOCTOR
        if (players.any { it.alive && it.role == Role.COMMISSIONER }) nightQueue += Role.COMMISSIONER
        if (players.any { it.alive && it.role == Role.ANGEL }) nightQueue += Role.ANGEL
        if (!witchUsed && players.any { it.alive && it.role == Role.WITCH }) nightQueue += Role.WITCH
        if (players.any { it.alive && it.role == Role.MANIAC }) nightQueue += Role.MANIAC

        startNextStep(now)
    }

    private fun startNextStep(now: Long) {
        step = null
        while (nightQueue.isNotEmpty()) {
            val role = nightQueue.removeAt(0)
            val s = buildNightStep(role, now) ?: continue
            // Отключившиеся игроки не могут сделать ход — считаем, что они пропустили.
            s.actorIds.filter { id -> playerById(id)?.left == true }
                .forEach { s.submissions[it] = emptyList() }
            if (s.actorIds.isNotEmpty() && s.actorIds.all { s.submissions.containsKey(it) }) {
                resolveStep(s, now)
                s.done = true
                s.doneAt = now + 1_200L
            }
            step = s
            phaseEndsAt = if (s.done) s.doneAt else s.endsAt
            events = mutableListOf(s.title)
            addLog("Просыпается: ${s.role.title}")
            return
        }
        finishNight(now)
    }

    private fun buildNightStep(role: Role, now: Long): NightStep? {
        return when (role) {
            Role.DRUGGIE -> {
                val actor = singleAlive(Role.DRUGGIE) ?: return null
                if (actor.id in drugBlocked || actor.id in beautyBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers().filter { it.id != actor.id },
                        title = "💊 Просыпается Наркоман",
                        body = "Наркоман выбирает, кому подсыпать порошок.",
                        prompt = "Кому подсыпать порошок?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.BEAUTY -> {
                val actor = singleAlive(Role.BEAUTY) ?: return null
                if (actor.id in drugBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers().filter { it.id != actor.id },
                        title = "💋 Просыпается Красотка",
                        body = "Красотка выбирает, к кому пойти. Цель пропустит голосование.",
                        prompt = "К кому пойти?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.MAFIA -> {
                val team = alivePlayers().filter { it.role?.isMafia == true }
                if (team.isEmpty()) return null
                val advocate = singleAlive(Role.ADVOCATE)
                val infoIds = (team.map { it.id } + listOfNotNull(advocate?.id)).toSet()
                val blocked = team.filter { it.id in drugBlocked || it.id in beautyBlocked }
                val active = team.filter { it.id !in drugBlocked && it.id !in beautyBlocked }
                val composition = buildString {
                    append("Состав мафии: ")
                    append(team.joinToString { "${it.role?.icon ?: ""} ${it.name}" })
                    if (advocate != null) append("\nАдвокат: ${advocate.name}")
                }
                if (active.isEmpty()) {
                    NightStep(
                        role = role,
                        kind = StepKind.INFO,
                        actorIds = emptyList(),
                        infoIds = infoIds,
                        blockedIds = blocked.map { it.id }.toSet(),
                        targetIds = emptyList(),
                        title = "🔴 Просыпается Мафия",
                        body = "$composition\n\nМафия заблокирована этой ночью — убийства не будет.",
                        promptTitle = "",
                        canSkip = false,
                        minTargets = 0,
                        maxTargets = 0,
                        endsAt = now + INFO_MS,
                        done = true,
                        doneAt = now + INFO_MS
                    )
                } else {
                    val targets = alivePlayers().filter {
                        it.role?.isMafia != true && it.role != Role.ADVOCATE
                    }
                    actionStep(
                        role = role,
                        kind = StepKind.TEAM,
                        actors = active,
                        infoIds = infoIds,
                        blocked = blocked.map { it.id }.toSet(),
                        targets = targets,
                        title = "🔴 Просыпается Мафия",
                        body = composition +
                            if (blocked.isNotEmpty())
                                "\nЗаблокированы: ${blocked.joinToString { it.name }}"
                            else "",
                        prompt = "Мафия выбирает жертву",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.DOCTOR -> {
                val actor = singleAlive(Role.DOCTOR) ?: return null
                if (actor.id in drugBlocked || actor.id in beautyBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers(),
                        title = "💚 Просыпается Доктор",
                        body = "Доктор выбирает, кого лечить. Можно выбрать себя.",
                        prompt = "Кого лечить?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.COMMISSIONER -> {
                val actor = singleAlive(Role.COMMISSIONER) ?: return null
                if (actor.id in drugBlocked || actor.id in beautyBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers().filter { it.id != actor.id },
                        title = "🔵 Просыпается Комиссар",
                        body = "Комиссар проверяет одного игрока. Дон мафии выглядит мирным.",
                        prompt = "Кого проверить?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.ANGEL -> {
                val actor = singleAlive(Role.ANGEL) ?: return null
                val dead = players.filter { !it.alive }
                when {
                    actor.id in drugBlocked || actor.id in beautyBlocked ->
                        blockedStep(role, actor, now)
                    nightNum < 2 -> infoStep(
                        role, actor, "😇 Просыпается Ангел",
                        "Воскрешение доступно только со 2-й ночи. Ангел ждёт.", now
                    )
                    dead.isEmpty() -> infoStep(
                        role, actor, "😇 Просыпается Ангел",
                        "Погибших нет — воскрешать некого.", now
                    )
                    else -> actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = dead,
                        title = "😇 Просыпается Ангел",
                        body = "Ангел может воскресить одного погибшего игрока.",
                        prompt = "Кого воскресить?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            Role.WITCH -> {
                if (witchUsed) return null
                val actor = singleAlive(Role.WITCH) ?: return null
                if (actor.id in drugBlocked || actor.id in beautyBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers(),
                        title = "🧙 Просыпается Ведьма",
                        body = "Ведьма один раз за игру может поменять роли двух игроков местами.",
                        prompt = "Выберите двух игроков для обмена ролями",
                        canSkip = true,
                        minTargets = 2,
                        maxTargets = 2,
                        now = now
                    )
                }
            }
            Role.MANIAC -> {
                val actor = singleAlive(Role.MANIAC) ?: return null
                if (actor.id in drugBlocked || actor.id in beautyBlocked) {
                    blockedStep(role, actor, now)
                } else {
                    actionStep(
                        role = role,
                        kind = StepKind.ACTION,
                        actors = listOf(actor),
                        infoIds = setOf(actor.id),
                        blocked = emptySet(),
                        targets = alivePlayers().filter { it.id != actor.id },
                        title = "🔪 Просыпается Маньяк",
                        body = "Маньяк выбирает жертву.",
                        prompt = "Кого убить?",
                        canSkip = true,
                        minTargets = 1,
                        maxTargets = 1,
                        now = now
                    )
                }
            }
            else -> null
        }
    }

    private fun actionStep(
        role: Role,
        kind: StepKind,
        actors: List<GamePlayer>,
        infoIds: Set<String>,
        blocked: Set<String>,
        targets: List<GamePlayer>,
        title: String,
        body: String,
        prompt: String,
        canSkip: Boolean,
        minTargets: Int,
        maxTargets: Int,
        now: Long
    ) = NightStep(
        role = role,
        kind = kind,
        actorIds = actors.map { it.id },
        infoIds = infoIds,
        blockedIds = blocked,
        targetIds = targets.map { it.id },
        title = title,
        body = body,
        promptTitle = prompt,
        canSkip = canSkip,
        minTargets = minTargets,
        maxTargets = maxTargets,
        endsAt = now + NIGHT_ACTION_MS
    )

    private fun blockedStep(role: Role, actor: GamePlayer, now: Long) = NightStep(
        role = role,
        kind = StepKind.INFO,
        actorIds = emptyList(),
        infoIds = setOf(actor.id),
        blockedIds = setOf(actor.id),
        targetIds = emptyList(),
        title = "${role.icon} Просыпается ${role.title}",
        body = "Вас заблокировали этой ночью — действие не состоится.",
        promptTitle = "",
        canSkip = false,
        minTargets = 0,
        maxTargets = 0,
        endsAt = now + INFO_MS,
        done = true,
        doneAt = now + INFO_MS
    )

    private fun infoStep(role: Role, actor: GamePlayer, title: String, body: String, now: Long) =
        NightStep(
            role = role,
            kind = StepKind.INFO,
            actorIds = emptyList(),
            infoIds = setOf(actor.id),
            blockedIds = emptySet(),
            targetIds = emptyList(),
            title = title,
            body = body,
            promptTitle = "",
            canSkip = false,
            minTargets = 0,
            maxTargets = 0,
            endsAt = now + INFO_MS,
            done = true,
            doneAt = now + INFO_MS
        )

    private fun resolveStep(s: NightStep, now: Long) {
        if (s.kind == StepKind.INFO) return
        when (s.role) {
            Role.DRUGGIE -> {
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (target != null) {
                    drugTargetId = target.id
                    drugBlocked += target.id
                    addLog("Наркоман выбрал ${target.name}")
                }
            }
            Role.BEAUTY -> {
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (target != null) {
                    beautyTargetId = target.id
                    beautyBlocked += target.id
                    addLog("Красотка выбрала ${target.name}")
                }
            }
            Role.MAFIA -> {
                val votes = mutableMapOf<String, Int>()
                s.submissions.values.flatten().forEach { votes[it] = (votes[it] ?: 0) + 1 }
                val top = votes.values.maxOrNull() ?: 0
                if (top > 0) {
                    pendingMafiaTargetId = votes.filterValues { it == top }.keys.random()
                }
                addLog("Мафия выбрала: ${pendingMafiaTargetId?.let { playerById(it)?.name } ?: "никого"}")
            }
            Role.DOCTOR -> {
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (target != null) {
                    doctorTargetId = target.id
                    addLog("Доктор выбрал ${target.name}")
                }
            }
            Role.COMMISSIONER -> {
                val actor = s.actorIds.firstOrNull()?.let(::playerById)
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (actor != null && target != null) {
                    commissionerCheckedId = target.id
                    val isMafia = target.role?.team == Team.MAFIA && target.role != Role.DON
                    privateMessages[actor.id] = if (isMafia) {
                        "🔵 Проверка: ${target.name} — МАФИЯ"
                    } else {
                        "🔵 Проверка: ${target.name} — мирный"
                    }
                    addLog("Комиссар проверил ${target.name}: ${if (isMafia) "мафия" else "мирный"}")
                }
            }
            Role.ANGEL -> {
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (target != null) {
                    angelReviveId = target.id
                    addLog("Ангел выбрал ${target.name}")
                }
            }
            Role.WITCH -> {
                val ids = s.submissions.values.flatten().distinct()
                if (ids.size == 2) {
                    val a = playerById(ids[0])
                    val b = playerById(ids[1])
                    if (a != null && b != null) {
                        val ra = a.role
                        a.role = b.role
                        b.role = ra
                        witchUsed = true
                        witchEvent = "🧙 Ведьма поменяла роли ${a.name} и ${b.name} местами."
                        privateMessages[a.id] =
                            "🧙 Ведьма поменяла вашу роль. Теперь вы: ${a.role?.icon ?: ""} ${a.role?.title ?: "?"}"
                        privateMessages[b.id] =
                            "🧙 Ведьма поменяла вашу роль. Теперь вы: ${b.role?.icon ?: ""} ${b.role?.title ?: "?"}"
                        addLog(witchEvent ?: "")
                    }
                }
            }
            Role.MANIAC -> {
                val target = s.submissions.values.flatten().firstOrNull()?.let(::playerById)
                if (target != null) {
                    pendingManiacTargetId = target.id
                    addLog("Маньяк выбрал ${target.name}")
                }
            }
            else -> Unit
        }
    }

    private fun finishNight(now: Long) {
        phase = Phase.NIGHT_RESULTS
        phaseEndsAt = null
        step = null
        events = mutableListOf()
        var someoneDied = false

        val revived = angelReviveId?.let(::playerById)
        if (revived != null && !revived.alive) {
            revived.alive = true
            events += "😇 Ангел воскресил ${revived.name}."
            addLog("Ангел воскресил ${revived.name}")
        }

        val mafiaTarget = pendingMafiaTargetId?.let(::playerById)
        if (mafiaTarget != null) {
            when {
                mafiaTarget.id == revived?.id ->
                    events += "🔴 Мафия выбрала ${mafiaTarget.name}, но ангел вернул его в игру."
                mafiaTarget.id == doctorTargetId ->
                    events += "💚 Доктор спас ${mafiaTarget.name} от мафии."
                mafiaTarget.alive -> {
                    mafiaTarget.alive = false
                    someoneDied = true
                    events += "🔴 Мафия убила ${mafiaTarget.name}."
                }
            }
        }

        val maniacTarget = pendingManiacTargetId?.let(::playerById)
        if (maniacTarget != null) {
            when {
                maniacTarget.id == revived?.id ->
                    events += "🔪 Маньяк выбрал ${maniacTarget.name}, но ангел вернул его в игру."
                maniacTarget.id == doctorTargetId ->
                    events += "💚 Доктор спас ${maniacTarget.name} от маньяка."
                !maniacTarget.alive ->
                    events += "🔪 Маньяк тоже выбрал ${maniacTarget.name}, но тот уже был мёртв."
                else -> {
                    maniacTarget.alive = false
                    someoneDied = true
                    events += "🔪 Маньяк убил ${maniacTarget.name}."
                }
            }
        }

        val doctorTarget = doctorTargetId?.let(::playerById)
        if (doctorTarget != null &&
            doctorTargetId != pendingMafiaTargetId &&
            doctorTargetId != pendingManiacTargetId
        ) {
            events += "💚 Доктор лечил ${doctorTarget.name}."
        }

        val beautyTarget = beautyTargetId?.let(::playerById)
        if (beautyTarget != null && beautyTarget.alive) {
            events += "💋 Красотка была у ${beautyTarget.name}. Он(а) пропускает голосование."
        }

        val drugTarget = drugTargetId?.let(::playerById)
        if (drugTarget != null) {
            events += "💊 Наркоман подсыпал порошок ${drugTarget.name}. Его(её) действие заблокировано."
        }

        if (commissionerCheckedId != null) {
            events += "🔵 Комиссар сделал проверку."
        }

        witchEvent?.let { events += it }

        if (!someoneDied && revived == null) {
            events += "Этой ночью никто не погиб."
        }

        winner = checkWinner()
        if (winner != null) events += winnerText()
        events.forEach { addLog("• $it") }
    }

    private fun beginDay(now: Long) {
        if (winner != null) {
            gameOver()
            return
        }
        phase = Phase.DAY_DISCUSSION
        dayNum = nightNum
        step = null
        phaseEndsAt = now + DISCUSSION_MS
        events = mutableListOf(
            "☀️ Город просыпается...",
            "У вас 60 секунд на обсуждение."
        )
        addLog("День $dayNum: обсуждение")
    }

    private fun beginVoting(now: Long) {
        if (winner != null) {
            gameOver()
            return
        }
        phase = Phase.VOTING
        step = null
        voteOrder.clear()
        voteCounts.clear()
        voteChoices.clear()
        voteIndex = 0
        phaseEndsAt = null

        val beautyTarget = beautyTargetId?.let(::playerById)
        alivePlayers().forEach {
            if (it.left) return@forEach
            if (beautyTarget == null || it.id != beautyTarget.id) voteOrder += it.id
        }

        events = mutableListOf("🗳️ Голосование")
        if (beautyTarget != null) {
            events += "${beautyTarget.name} пропускает голосование (Красотка)."
        }

        if (voteOrder.isEmpty()) {
            finishVoting(now)
            return
        }

        voteEndsAt = now + VOTE_MS
        phaseEndsAt = voteEndsAt
        addLog("Голосование: первым голосует ${currentVoter()?.name ?: "—"}")
    }

    private fun recordVote(targetId: String?, now: Long) {
        val voter = currentVoter()
        if (voter == null) {
            finishVoting(now)
            return
        }
        if (voteChoices.containsKey(voter.id)) return

        voteChoices[voter.id] = targetId
        if (targetId != null) {
            val target = playerById(targetId)
            if (target != null && target.alive) {
                val weight = if (voter.role == Role.MAYOR) 2 else 1
                voteCounts[target.id] = (voteCounts[target.id] ?: 0) + weight
                addLog("${voter.name} голосует против ${target.name}${if (weight == 2) " (x2 мэр)" else ""}")
            }
        } else {
            addLog("${voter.name} пропускает голосование")
        }

        voteIndex += 1
        if (voteIndex >= voteOrder.size) {
            finishVoting(now)
        } else {
            voteEndsAt = now + VOTE_MS
            phaseEndsAt = voteEndsAt
        }
    }

    private fun finishVoting(now: Long) {
        phase = Phase.VOTE_RESULTS
        phaseEndsAt = null
        voteEndsAt = 0
        events = mutableListOf()

        val top = voteCounts.values.maxOrNull() ?: 0
        val leaders = if (top > 0) voteCounts.filterValues { it == top }.keys.toList() else emptyList()

        if (leaders.size == 1) {
            val eliminated = playerById(leaders[0])
            if (eliminated != null) {
                eliminated.alive = false
                events += "🗳️ ${eliminated.name} исключён из игры."
                events += "Его роль: ${eliminated.role?.icon ?: ""} ${eliminated.role?.title ?: "?"}."
                addLog("Исключён ${eliminated.name} (${eliminated.role?.title})")
                if (eliminated.role == Role.JESTER) {
                    jesterWon = true
                    events += "🃏 Это был Шут! Шут добился своего и побеждает!"
                }
            }
        } else {
            events += "Голоса разделились — никто не исключён."
            addLog("Голоса разделились")
        }

        if (voteCounts.isNotEmpty()) {
            events += "Итоги голосования:"
            voteCounts.entries.sortedByDescending { it.value }.forEach { (id, count) ->
                val name = playerById(id)?.name ?: "?"
                events += "• $name — $count"
            }
        }

        winner = checkWinner()
        if (winner != null) events += winnerText()
        events.forEach { addLog("• $it") }
    }

    private fun gameOver() {
        if (phase == Phase.GAME_OVER) return
        phase = Phase.GAME_OVER
        phaseEndsAt = null
        step = null
        rolesRevealed = true
        if (winner == null) winner = checkWinner() ?: "peaceful"
        val text = winnerText()
        if (events.none { it == text }) events += text
        addLog("Игра окончена: $winner")
    }

    /** Возвращает комнату в лобби, сохраняя подключённых игроков. */
    fun restartToLobby(): ActionResult = synchronized(lock) {
        if (phase != Phase.GAME_OVER) return@synchronized ActionResult(false, "Игра ещё идёт")
        players.removeAll { it.left }
        players.forEach {
            it.role = null
            it.alive = true
            it.left = false
        }
        phase = Phase.LOBBY
        startAt = null
        phaseEndsAt = null
        step = null
        nightQueue.clear()
        voteOrder.clear()
        voteCounts.clear()
        voteChoices.clear()
        voteIndex = 0
        voteEndsAt = 0L
        nightNum = 1
        dayNum = 1
        winner = null
        jesterWon = false
        rolesRevealed = false
        witchUsed = false
        sniperUsed = false
        drugBlocked.clear()
        beautyBlocked.clear()
        privateMessages.clear()
        events = mutableListOf("↩️ Комната вернулась в лобби")
        addLog("Ведущий вернул комнату в лобби")
        emit()
        ActionResult(true)
    }

    private fun checkWinner(): String? {
        if (jesterWon) return "jester"
        val alive = alivePlayers()
        val mafia = alive.count { it.role?.team == Team.MAFIA }
        val maniac = alive.count { it.role == Role.MANIAC }
        val peaceful = alive.count {
            it.role?.team == Team.PEACEFUL || it.role?.team == Team.JESTER
        }
        if (mafia == 0 && maniac == 0) return "peaceful"
        if (maniac == 0 && mafia >= peaceful) return "mafia"
        if (maniac > 0 && maniac >= alive.size - maniac) return "maniac"
        return null
    }

    private fun winnerText(): String = when (winner) {
        "mafia" -> "🔴 МАФИЯ ПОБЕДИЛА!"
        "maniac" -> "🔪 МАНЬЯК ПОБЕДИЛ!"
        "jester" -> "🃏 ШУТ ПОБЕДИЛ!"
        "peaceful" -> "✅ МИРНЫЕ ПОБЕДИЛИ!"
        else -> "Игра окончена."
    }

    // ─────────────────────────────────────────
    //  Снимки для UI
    // ─────────────────────────────────────────

    private fun emptySnapshot() = HostSnapshot(
        roomName = config.name,
        phase = Phase.LOBBY.id,
        phaseLabel = "Лобби",
        nightNum = 1,
        dayNum = 1,
        players = emptyList(),
        maxPlayers = config.maxPlayers,
        hasPassword = !config.password.isNullOrBlank(),
        serverNow = System.currentTimeMillis(),
        canStart = false,
        allowedRoles = config.allowedRoles.toList()
    )

    private fun emit(now: Long = System.currentTimeMillis()) {
        _hostSnapshot.value = hostSnapshotLocked(now)
    }

    private fun hostSnapshotLocked(now: Long): HostSnapshot {
        val views = players.map { p ->
            PlayerView(
                id = p.id,
                name = p.name,
                device = p.device,
                alive = p.alive,
                roleId = p.role?.id,
                roleTitle = p.role?.title,
                roleIcon = p.role?.icon,
                roleDescription = p.role?.description,
                roleTeam = p.role?.team?.name?.lowercase(),
                isYou = false,
                left = p.left
            )
        }

        val s = step
        val progress = when {
            phase == Phase.NIGHT_STEP && s != null ->
                if (s.kind == StepKind.INFO) "Информация"
                else "Выбрали: ${s.submissions.size} из ${s.actorIds.size}"
            phase == Phase.VOTING -> "Проголосовали: $voteIndex из ${voteOrder.size}"
            phase == Phase.DAY_DISCUSSION &&
                players.any { it.alive && it.role == Role.SNIPER } &&
                !sniperUsed -> "Снайпер может выстрелить"
            else -> null
        }

        val canAdvance = phase in setOf(
            Phase.STARTING, Phase.NIGHT_INTRO, Phase.NIGHT_STEP,
            Phase.NIGHT_RESULTS, Phase.DAY_DISCUSSION, Phase.VOTING, Phase.VOTE_RESULTS
        )

        val label = when (phase) {
            Phase.STARTING -> "Пропустить отсчёт"
            Phase.NIGHT_INTRO -> "Далее"
            Phase.NIGHT_STEP -> "Пропустить ход"
            Phase.NIGHT_RESULTS -> if (winner != null) "Финал" else "Рассвет"
            Phase.DAY_DISCUSSION -> "К голосованию"
            Phase.VOTING -> "Пропустить голос"
            Phase.VOTE_RESULTS -> if (winner != null) "Финал" else "Следующая ночь"
            else -> "Далее"
        }

        return HostSnapshot(
            roomName = config.name,
            phase = phase.id,
            phaseLabel = phaseLabel(),
            nightNum = nightNum,
            dayNum = dayNum,
            players = views,
            maxPlayers = config.maxPlayers,
            hasPassword = !config.password.isNullOrBlank(),
            serverNow = now,
            startAt = if (phase == Phase.STARTING) startAt else null,
            phaseEndsAt = phaseEndsAtForClient(),
            events = events.toList(),
            log = log.takeLast(80),
            nightTitle = s?.title,
            nightBody = s?.body,
            actionProgress = progress,
            canAdvance = canAdvance,
            advanceLabel = label,
            winner = winner,
            vote = voteStatus(),
            canStart = phase == Phase.LOBBY && players.count { !it.left } >= MIN_PLAYERS,
            canRestart = phase == Phase.GAME_OVER,
            allowedRoles = config.allowedRoles.toList()
        )
    }

    fun clientState(playerId: String, token: String): ClientState? = synchronized(lock) {
        val viewer = players.firstOrNull { it.id == playerId && it.token == token } ?: return@synchronized null
        val now = System.currentTimeMillis()
        val viewerIsMafia = viewer.role?.team == Team.MAFIA
        val viewerIsAdvocate = viewer.role == Role.ADVOCATE
        val reveal = rolesRevealed

        fun roleVisibleFor(other: GamePlayer): Boolean {
            if (reveal || other.id == viewer.id) return true
            if (other.role?.team == Team.MAFIA && (viewerIsMafia || viewerIsAdvocate)) return true
            return false
        }

        fun viewOf(other: GamePlayer): PlayerView {
            val show = roleVisibleFor(other)
            return PlayerView(
                id = other.id,
                name = other.name,
                device = other.device,
                alive = other.alive,
                roleId = if (show) other.role?.id else null,
                roleTitle = if (show) other.role?.title else null,
                roleIcon = if (show) other.role?.icon else null,
                roleDescription = if (show) other.role?.description else null,
                roleTeam = if (show) other.role?.team?.name?.lowercase() else null,
                isYou = other.id == viewer.id,
                left = other.left
            )
        }

        val views = players.map(::viewOf)
        val s = step
        val prompt = buildPrompt(viewer, now)

        ClientState(
            roomName = config.name,
            phase = phase.id,
            phaseLabel = phaseLabel(),
            you = viewOf(viewer),
            players = views,
            playerCount = players.size,
            maxPlayers = config.maxPlayers,
            hasPassword = !config.password.isNullOrBlank(),
            serverNow = now,
            startAt = if (phase == Phase.STARTING) startAt else null,
            phaseEndsAt = phaseEndsAtForClient(),
            prompt = prompt,
            message = buildMessage(viewer, prompt),
            privateMessage = privateMessages[viewer.id],
            events = events.toList(),
            vote = voteStatus(),
            winner = winner,
            rolesRevealed = rolesRevealed,
            nightRoleTitle = s?.role?.title,
            nightRoleIcon = s?.role?.icon
        )
    }

    private fun buildPrompt(viewer: GamePlayer, now: Long): ActionPrompt? {
        val s = step
        if (phase == Phase.NIGHT_STEP && s != null &&
            s.kind != StepKind.INFO &&
            !s.done &&
            viewer.id in s.actorIds &&
            !s.submissions.containsKey(viewer.id)
        ) {
            val targets = s.targetIds.mapNotNull(::playerById).map {
                TargetView(it.id, it.name, it.device, it.alive)
            }
            return ActionPrompt(
                kind = if (s.kind == StepKind.TEAM) "mafia" else "night",
                title = s.promptTitle,
                targets = targets,
                canSkip = s.canSkip,
                minTargets = s.minTargets,
                maxTargets = s.maxTargets,
                endsAt = s.endsAt
            )
        }

        if (phase == Phase.DAY_DISCUSSION) {
            val sniper = players.firstOrNull { it.alive && it.role == Role.SNIPER }
            if (sniper != null && sniper.id == viewer.id && !sniperUsed) {
                val targets = alivePlayers()
                    .filter { it.id != viewer.id }
                    .map { TargetView(it.id, it.name, it.device, it.alive) }
                return ActionPrompt(
                    kind = "sniper",
                    title = "🎯 Один выстрел за игру. Если попадёте в мирного — погибнете сами.",
                    targets = targets,
                    canSkip = true,
                    minTargets = 1,
                    maxTargets = 1,
                    endsAt = phaseEndsAt ?: 0L
                )
            }
        }

        if (phase == Phase.VOTING) {
            val voter = currentVoter()
            if (voter != null && voter.id == viewer.id) {
                val targets = alivePlayers()
                    .filter { it.id != viewer.id }
                    .map { TargetView(it.id, it.name, it.device, it.alive) }
                return ActionPrompt(
                    kind = "vote",
                    title = "Ваш голос. Кого исключить?",
                    targets = targets,
                    canSkip = true,
                    minTargets = 1,
                    maxTargets = 1,
                    endsAt = voteEndsAt
                )
            }
        }

        return null
    }

    private fun buildMessage(viewer: GamePlayer, prompt: ActionPrompt?): String {
        val s = step
        return when (phase) {
            Phase.LOBBY -> "Ожидаем начала игры. Ведущий собирает состав."
            Phase.STARTING ->
                "Ваша роль: ${viewer.role?.icon ?: ""} ${viewer.role?.title ?: "?"}. " +
                    "Игра начинается через 10 секунд..."
            Phase.NIGHT_INTRO -> "🌙 Город засыпает..."
            Phase.NIGHT_STEP -> {
                if (s == null) "Город спит..."
                else if (viewer.id in s.actorIds) {
                    if (s.submissions.containsKey(viewer.id)) "Выбор сделан. Ожидайте."
                    else if (prompt != null) "" else "Ваш ход."
                } else if (viewer.id in s.infoIds) s.body
                else if (!viewer.alive) "Вы наблюдаете за игрой."
                else "Город спит. Просыпается: ${s.role.title}"
            }
            Phase.NIGHT_RESULTS -> "☀️ Наступает утро. Итоги ночи ниже."
            Phase.DAY_DISCUSSION ->
                if (!viewer.alive) "Вы наблюдаете за игрой."
                else "Обсуждение. У вас 60 секунд, чтобы договориться."
            Phase.VOTING -> {
                val voter = currentVoter()
                when {
                    !viewer.alive -> "Голосование идёт без вас."
                    voter?.id == viewer.id -> "Ваш голос решает. Выберите, кого исключить."
                    voter != null -> "Голосует: ${voter.name}"
                    else -> "Голосование заканчивается..."
                }
            }
            Phase.VOTE_RESULTS -> "Итоги голосования."
            Phase.GAME_OVER -> winnerText()
        }
    }

    private fun phaseLabel(): String = when (phase) {
        Phase.LOBBY -> "Лобби"
        Phase.STARTING -> "Раздача ролей"
        Phase.NIGHT_INTRO -> "Ночь $nightNum"
        Phase.NIGHT_STEP -> "Ночь $nightNum"
        Phase.NIGHT_RESULTS -> "Итоги ночи"
        Phase.DAY_DISCUSSION -> "День $dayNum"
        Phase.VOTING -> "Голосование"
        Phase.VOTE_RESULTS -> "Итоги дня"
        Phase.GAME_OVER -> "Игра окончена"
    }

    private fun phaseEndsAtForClient(): Long? = when (phase) {
        Phase.STARTING -> startAt
        Phase.NIGHT_STEP -> step?.let { if (it.done) it.doneAt else it.endsAt }
        Phase.DAY_DISCUSSION -> phaseEndsAt
        Phase.VOTING -> voteEndsAt
        else -> null
    }

    private fun voteStatus(): VoteStatus? {
        return when (phase) {
            Phase.VOTING -> {
                val voter = currentVoter()
                VoteStatus(
                    voterId = voter?.id,
                    voterName = voter?.name,
                    endsAt = voteEndsAt,
                    done = voteIndex,
                    total = voteOrder.size
                )
            }
            Phase.VOTE_RESULTS -> VoteStatus(
                voterId = null,
                voterName = null,
                endsAt = 0,
                done = voteOrder.size,
                total = voteOrder.size
            )
            else -> null
        }
    }

    // ─────────────────────────────────────────
    //  Утилиты
    // ─────────────────────────────────────────

    private fun auth(playerId: String, token: String): GamePlayer? =
        players.firstOrNull { it.id == playerId && it.token == token }

    private fun playerById(id: String): GamePlayer? = players.firstOrNull { it.id == id }

    private fun alivePlayers(): List<GamePlayer> = players.filter { it.alive }

    private fun singleAlive(role: Role): GamePlayer? =
        players.firstOrNull { it.alive && it.role == role }

    private fun currentVoter(): GamePlayer? =
        voteOrder.getOrNull(voteIndex)?.let(::playerById)

    private fun addLog(text: String) {
        if (text.isBlank()) return
        log += text
        if (log.size > 200) log.removeAt(0)
    }

    /**
     * Колода ролей.
     * Мафия обязательна всегда. Спецроли берутся из выбранных ведущим.
     * Остальные слоты — мирные.
     */
    private fun buildDeck(n: Int): List<Role> {
        val allowed = config.allowedRoles.mapNotNull { Role.byId(it) }.toMutableSet()
        if (allowed.isEmpty()) allowed += Role.MAFIA

        val mafiaCount = max(1, n / 3)
        val deck = mutableListOf<Role>()

        if (Role.DON in allowed && mafiaCount >= 2 && n >= 8) {
            deck += Role.DON
            repeat(mafiaCount - 1) { deck += Role.MAFIA }
        } else {
            repeat(mafiaCount) { deck += Role.MAFIA }
        }

        val specials = allowed
            .filter { it.team != Team.MAFIA && it != Role.CIVILIAN }
            .shuffled()
        val remaining = n - deck.size
        val specialSlots = max(0, minOf(specials.size, remaining - 1))
        deck += specials.take(specialSlots)

        while (deck.size < n) deck += Role.CIVILIAN
        deck.shuffle()
        return deck.take(n)
    }
}
