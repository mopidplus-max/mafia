(() => {
  'use strict';

  const app = document.getElementById('app');
  const SESSION_KEY = 'mafia_session_v1';

  // Режим просмотра для ведущего: страница открыта как экран игрока,
  // без права нажимать кнопки и без записи в localStorage.
  const watchParams = new URLSearchParams(location.search);
  const WATCH = watchParams.get('watch') === '1'
    ? { pid: watchParams.get('pid') || '', token: watchParams.get('token') || '' }
    : null;

  // Прогреваем картинку котости, чтобы полноэкранная анимация открылась сразу.
  try { new Image().src = '/assets/cat.png'; } catch (_) {}

  const ua = navigator.userAgent || '';
  const isIOS = /iPad|iPhone|iPod/.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const device = isIOS ? 'ios' : (/Android/i.test(ua) ? 'android' : 'web');
  const deviceName = isIOS ? 'iPhone' : (device === 'android' ? 'Android' : 'браузер');

  let roomInfo = null;
  let session = null;
  let state = null;
  let lastSig = '';
  let serverOffset = 0;
  let pollTimer = null;
  let activityTimer = null;
  let connectionLost = false;
  let joining = false;
  let joinError = '';
  let actionError = '';

  let currentPromptSig = '';
  let selection = new Set();
  let sentSig = '';

  // Чат живёт отдельно от #app, чтобы ввод не терял фокус при перерисовке.
  let chatSig = '';
  let chatOpen = false;
  let unread = 0;
  let lastSeenChatId = 0;

  // Звук и кинематографичные вставки.
  let audioCtx = null;
  let lastSoundSeq = 0;
  let lastCatSeq = 0;
  let roleCinemaShown = false;
  let mp3Player = null;
  let mp3Unlocked = false;
  // Короткая тишина: разблокирует <audio> на iPhone после первого касания.
  const SILENT_WAV = 'data:audio/wav;base64,UklGRrQBAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YZABAACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICA';

  // ─────────────────────────────────────────
  //  API
  // ─────────────────────────────────────────

  async function api(path, opts = {}) {
    const headers = { Accept: 'application/json' };
    if (opts.body) headers['Content-Type'] = 'application/json; charset=utf-8';
    const response = await fetch(path, { ...opts, headers });
    const text = await response.text();
    let data = {};
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (_) {
        data = { error: text };
      }
    }
    if (!response.ok) {
      throw new Error(data.error || ('HTTP ' + response.status));
    }
    return data;
  }

  function originKey() {
    return location.origin;
  }

  function stateUrl() {
    return '/api/state?pid=' + encodeURIComponent(session.playerId) +
      '&token=' + encodeURIComponent(session.token);
  }

  function saveSession(value) {
    if (WATCH) return;
    try {
      localStorage.setItem(SESSION_KEY, JSON.stringify(value));
    } catch (_) {}
  }

  function loadSession() {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      if (!raw) return null;
      const value = JSON.parse(raw);
      if (!value || value.origin !== originKey() || !value.playerId || !value.token) return null;
      return value;
    } catch (_) {
      return null;
    }
  }

  function clearSession() {
    if (WATCH) return;
    try {
      localStorage.removeItem(SESSION_KEY);
    } catch (_) {}
  }

  // ─────────────────────────────────────────
  //  Запуск
  // ─────────────────────────────────────────

  async function boot() {
    joinError = '';
    actionError = '';
    if (WATCH) {
      if (!WATCH.pid || !WATCH.token) {
        fatal('Нет данных для просмотра');
        return;
      }
      session = { playerId: WATCH.pid, token: WATCH.token, name: 'watch' };
      try {
        const snapshot = await api(stateUrl());
        enterGame(snapshot);
      } catch (e) {
        fatal('Экран недоступен: ' + (e.message || 'сессия истекла'));
      }
      return;
    }
    try {
      const data = await api('/api/room');
      roomInfo = data.room;
    } catch (e) {
      fatal(e.message || 'Не удалось открыть комнату');
      return;
    }

    const saved = loadSession();
    if (saved) {
      session = saved;
      try {
        const snapshot = await api(stateUrl());
        enterGame(snapshot);
        return;
      } catch (_) {
        clearSession();
        session = null;
      }
    }
    renderJoin();
  }

  function enterGame(snapshot) {
    state = snapshot;
    serverOffset = snapshot.serverNow - Date.now();
    lastSig = stateSig(snapshot);
    lastSeenChatId = (snapshot.chat || []).reduce((max, m) => Math.max(max, m.id || 0), 0);
    lastSoundSeq = snapshot.sound ? snapshot.sound.seq : 0;
    lastCatSeq = snapshot.catSave ? snapshot.catSave.seq : 0;
    roleCinemaShown = false;
    connectionLost = false;
    joining = false;
    render();
    startPolling();
    startActivityReporting();
  }

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(poll, 1200);
    poll();
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function startActivityReporting() {
    stopActivityReporting();
    if (WATCH) return;
    activityTimer = setInterval(reportActivity, 3000);
  }

  function stopActivityReporting() {
    if (activityTimer) {
      clearInterval(activityTimer);
      activityTimer = null;
    }
  }

  async function poll() {
    if (!session) return;
    try {
      const snapshot = await api(stateUrl());
      if (!snapshot || !snapshot.you) throw new Error('Пустой ответ сервера');
      state = snapshot;
      serverOffset = snapshot.serverNow - Date.now();
      const wasLost = connectionLost;
      connectionLost = false;
      handleEffects(snapshot);
      const sig = stateSig(snapshot);
      if (sig !== lastSig || wasLost) {
        lastSig = sig;
        render();
      } else {
        updateCountdowns();
      }
      renderChat();
    } catch (e) {
      const message = e.message || '';
      if (message.includes('Сессия') || message.includes('404')) {
        if (WATCH) {
          stopPolling();
          stopActivityReporting();
          fatal('Сессия игрока завершена');
          return;
        }
        clearSession();
        session = null;
        state = null;
        stopPolling();
        stopActivityReporting();
        renderChat();
        renderJoin('Сессия истекла. Войдите снова.');
        return;
      }
      if (!connectionLost) {
        connectionLost = true;
        render();
      }
    }
  }

  // ─────────────────────────────────────────
  //  Вход
  // ─────────────────────────────────────────

  function renderJoin(message) {
    if (message) joinError = message;
    if (!roomInfo) {
      fatal(joinError || 'Комната не найдена');
      return;
    }
    const full = roomInfo.maxPlayers > 0 && roomInfo.playerCount >= roomInfo.maxPlayers;
    const started = roomInfo.status !== 'lobby';
    const disabled = started || full;

    app.innerHTML = `
      <div class="screen">
        <div class="brand">
          <div class="mask">🎭</div>
          <h1>МАФИЯ</h1>
          <p>Играют все на сайте. Телефон ведущего и ваш телефон должны быть в одной сети.</p>
        </div>

        <div class="card hero">
          <div class="eyebrow">Комната</div>
          <h2>${esc(roomInfo.name)}</h2>
          <div class="row wrap" style="margin-top:10px">
            <span class="chip">👥 ${roomInfo.playerCount}${roomInfo.maxPlayers > 0 ? ' из ' + roomInfo.maxPlayers : ''}</span>
            ${roomInfo.hasPassword ? '<span class="chip gold">🔒 пароль</span>' : ''}
            <span class="chip">${deviceIcon(device)} ${esc(deviceName)}</span>
          </div>
        </div>

        ${started ? '<div class="banner error">Игра уже началась. Подключиться можно только в лобби.</div>' : ''}
        ${full && !started ? '<div class="banner error">В комнате нет свободных мест.</div>' : ''}
        ${joinError ? `<div class="banner error">${esc(joinError)}</div>` : ''}

        <form id="join-form" class="card">
          <label class="field">
            <span>Ваше имя</span>
            <input id="name" maxlength="24" autocomplete="nickname" placeholder="Как вас зовут?" required>
          </label>
          ${roomInfo.hasPassword ? `
            <label class="field">
              <span>Пароль комнаты</span>
              <input id="password" type="password" maxlength="32" placeholder="Введите пароль">
            </label>` : ''}
          <div style="height:16px"></div>
          <button class="btn primary" type="submit" ${disabled ? 'disabled' : ''}>
            ${joining ? 'Подключаемся...' : 'Подключиться'}
          </button>
        </form>

        <div class="hint center">Имя можно писать по-русски. Вход с телефона или компьютера.</div>
      </div>`;
  }

  async function join() {
    if (joining) return;
    const nameEl = document.getElementById('name');
    const passwordEl = document.getElementById('password');
    const name = (nameEl?.value || '').trim();
    if (!name) {
      joinError = 'Введите имя';
      renderJoin();
      return;
    }
    joining = true;
    joinError = '';
    const button = document.querySelector('#join-form button[type="submit"]');
    if (button) {
      button.disabled = true;
      button.textContent = 'Подключаемся...';
    }
    try {
      const data = await api('/api/join', {
        method: 'POST',
        body: JSON.stringify({
          name,
          password: passwordEl?.value || null,
          device
        })
      });
      if (!data.ok) throw new Error(data.error || 'Не удалось войти');
      session = {
        origin: originKey(),
        playerId: data.playerId,
        token: data.token
      };
      saveSession(session);
      const snapshot = await api(stateUrl());
      enterGame(snapshot);
    } catch (e) {
      joining = false;
      joinError = e.message || 'Не удалось подключиться';
      renderJoin();
    }
  }

  async function leave() {
    if (!confirm('Выйти из комнаты?')) return;
    const old = session;
    clearSession();
    session = null;
    state = null;
    stopPolling();
    stopActivityReporting();
    joining = false;
    joinError = '';
    actionError = '';
    chatSig = '';
    unread = 0;
    renderChat();
    if (old) {
      try {
        await api('/api/leave', {
          method: 'POST',
          body: JSON.stringify({ playerId: old.playerId, token: old.token })
        });
      } catch (_) {}
    }
    try {
      const data = await api('/api/room');
      roomInfo = data.room;
    } catch (_) {}
    renderJoin();
  }

  // ─────────────────────────────────────────
  //  Отрисовка
  // ─────────────────────────────────────────

  function render() {
    if (!state) {
      renderJoin();
      return;
    }
    document.body.classList.toggle('night',
      state.phase === 'night_intro' || state.phase === 'night_step' || state.phase === 'night_results');
    if (state.phase === 'lobby') {
      renderLobby();
    } else {
      renderGame();
    }
    handleEffects(state);
    renderChat();
    updateCountdowns();
  }

  function topbar() {
    return `
      <div class="topbar">
        <div class="grow">
          <div class="room">${esc(state.roomName)}</div>
          <div class="who">${deviceIcon(state.you.device)} ${esc(state.you.name)}${catInline(state.you)}</div>
        </div>
        <button class="icon-btn" data-action="leave" aria-label="Выйти">✕</button>
      </div>`;
  }

  function renderLobby() {
    const max = state.maxPlayers > 0 ? ' из ' + state.maxPlayers : '';
    app.innerHTML = `
      <div class="screen">
        ${topbar()}
        ${connectionLost ? '<div class="banner error">Связь с ведущим потеряна. Переподключаемся...</div>' : ''}
        <div class="card hero">
          <div class="eyebrow">Вы в комнате</div>
          <h2>${esc(state.roomName)}</h2>
          <div class="row wrap" style="margin-top:10px">
            <span class="chip">👥 ${state.playerCount}${max}</span>
            ${state.hasPassword ? '<span class="chip gold">🔒 пароль</span>' : ''}
          </div>
        </div>

        <h3>Участники</h3>
        <div class="list">${playersHtml(false)}</div>

        <div class="card waiting" style="margin-top:14px">
          <span class="spinner"></span>
          <span>Ждём ведущего. Игра начнётся, когда все соберутся.</span>
        </div>

        <div class="hint center">Общий чат открыт для всех — можно знакомиться и договариваться.</div>

        <div style="height:12px"></div>
        <button class="btn ghost" data-action="leave">Выйти из комнаты</button>
      </div>`;
  }

  function renderGame() {
    const prompt = state.prompt;
    const promptSig = promptSigOf(prompt);
    if (promptSig !== currentPromptSig) {
      currentPromptSig = promptSig;
      selection = new Set();
      sentSig = '';
    }

    const winner = state.winner;
    const role = state.you;
    const prominent = state.phase === 'starting';
    const roleClass = role.roleTeam || '';

    const roleHtml = `
      <div class="role-card ${prominent ? 'prominent' : ''} ${roleClass}">
        <div class="role-icon">${esc(role.roleIcon || '❔')}</div>
        <div class="role-title">${esc(role.roleTitle || 'Роль скрыта')}</div>
        ${role.guardian ? '<div class="guardian-tag">👼 Ангел-Хранитель</div>' : ''}
        ${prominent && role.roleDescription ? `<p class="muted center" style="margin:10px 0 0">${esc(role.roleDescription)}</p>` : ''}
      </div>`;

    const phaseHtml = winner ? '' : `
      <div class="card phase">
        <div class="phase-label">${esc(state.phaseLabel)}</div>
        ${state.message ? `<div class="phase-message">${esc(state.message)}</div>` : ''}
        ${state.phaseEndsAt ? `<div class="countdown" data-countdown data-ends="${state.phaseEndsAt}"></div>` : ''}
        ${state.nightRoleTitle ? `<div class="muted" style="margin-top:6px">Просыпается: ${esc(state.nightRoleIcon || '')} ${esc(state.nightRoleTitle)}</div>` : ''}
      </div>`;

    const promptHtml = (!winner && prompt) ? promptCardHtml(prompt, promptSig) : '';
    const voteHtml = (!winner && !prompt && state.vote) ? voteCardHtml(state.vote) : '';
    const privateHtml = state.privateMessage ? `
      <div class="banner gold">${esc(state.privateMessage)}</div>` : '';
    const eventsHtml = state.events && state.events.length ? `
      <div class="card events">
        <ul>${state.events.map(event => `<li>${esc(event)}</li>`).join('')}</ul>
      </div>` : '';

    app.innerHTML = `
      <div class="screen">
        ${topbar()}
        ${connectionLost ? '<div class="banner error">Связь с ведущим потеряна. Переподключаемся...</div>' : ''}
        ${actionError ? `<div class="banner error">${esc(actionError)}</div>` : ''}
        ${winner ? `<div class="winner">${esc(winnerText(winner))}</div>` : ''}
        ${roleHtml}
        ${phaseHtml}
        ${!state.you.alive && !winner && !prompt ? '<div class="banner">Вы выбыли из игры. Можно наблюдать за партией и писать в чат.</div>' : ''}
        ${promptHtml}
        ${privateHtml}
        ${voteHtml}
        ${eventsHtml}

        <h3>Игроки</h3>
        <div class="list">${playersHtml(true)}</div>

        <div style="height:12px"></div>
        <button class="btn ghost" data-action="leave">Выйти из комнаты</button>
      </div>`;

    syncPromptUi();
  }

  function promptHint(prompt) {
    if (prompt.kind === 'sniper') return 'Один выстрел за игру. Промах убьёт вас.';
    if (prompt.kind === 'pistol') return 'Пистолет можно использовать один раз за игру.';
    if (prompt.kind === 'vote') return 'Голос нельзя изменить.';
    if (prompt.kind === 'mafia') return 'Решение мафии принимается большинством.';
    const title = prompt.title || '';
    if (title.includes('Смертник')) return 'Вы погибнете, но вернёте выбранного игрока в игру.';
    if (title.includes('Хранитель')) return 'Защита действует только эту ночь.';
    return '';
  }

  function skipLabel(prompt) {
    if (prompt.kind === 'vote') return 'Воздержаться';
    if (prompt.kind === 'pistol') return 'Не сейчас';
    return 'Пропустить';
  }

  function promptCardHtml(prompt, sig) {
    const sent = sentSig === sig;
    const targets = (prompt.targets || []).map(target => {
      const selected = selection.has(target.id);
      return `
        <div class="target ${selected ? 'selected' : ''}" data-target="${esc(target.id)}">
          <div class="avatar">${deviceIcon(target.device)}</div>
          <div class="tname">${esc(target.name)}</div>
          <div class="check">${selected ? '✓' : ''}</div>
        </div>`;
    }).join('');

    if (sent) {
      return `
        <div class="card prompt">
          <div class="prompt-title">${esc(prompt.title)}</div>
          <div class="banner gold" style="margin-bottom:0">Выбор отправлен. Ожидайте остальных.</div>
        </div>`;
    }

    const hint = promptHint(prompt);

    return `
      <div class="card prompt">
        <div class="prompt-title">${esc(prompt.title)}</div>
        ${prompt.maxTargets > 1 ? `<div class="muted">Выберите ${prompt.maxTargets} игрока</div>` : ''}
        ${hint ? `<div class="hint">${esc(hint)}</div>` : ''}
        <div class="countdown" data-countdown data-ends="${prompt.endsAt}"></div>
        <div class="targets">${targets}</div>
        <div class="row between small muted" id="selected-count">Выбрано: ${selection.size} из ${prompt.maxTargets}</div>
        <div style="height:10px"></div>
        <button class="btn primary" data-action="submit-prompt" ${selection.size < prompt.minTargets ? 'disabled' : ''}>Подтвердить</button>
        ${prompt.canSkip ? `<div style="height:8px"></div><button class="btn ghost" data-action="skip-prompt">${skipLabel(prompt)}</button>` : ''}
      </div>`;
  }

  function voteCardHtml(vote) {
    const percent = vote.total > 0 ? Math.min(100, (vote.done / vote.total) * 100) : 0;
    return `
      <div class="card">
        <div class="row between">
          <strong>${vote.voterName ? 'Голосует: ' + esc(vote.voterName) : 'Голосование завершено'}</strong>
          <span class="chip">${vote.done}/${vote.total}</span>
        </div>
        <div class="progress" data-progress="${vote.done},${vote.total}">
          <i style="width:${percent}%"></i>
        </div>
        ${vote.voterName ? `<div class="countdown" data-countdown data-ends="${vote.endsAt}"></div>` : ''}
      </div>`;
  }

  function playersHtml(showRoles) {
    return (state.players || []).map(player => {
      const role = showRoles && player.roleTitle
        ? `<div class="role-tag ${esc(player.roleTeam || '')}">${esc(player.roleIcon || '')} ${esc(player.roleTitle)}</div>`
        : '';
      return `
        <div class="player ${player.alive ? '' : 'dead'}">
          <div class="avatar">${deviceIcon(player.device)}</div>
          <div class="pname">
            ${esc(player.name)}
            ${catInline(player)}
            ${player.isYou ? '<span class="you">вы</span>' : ''}
            ${player.guardian ? '<span class="dead-tag gold-text">👼 хранитель</span>' : ''}
            ${player.alive ? '' : '<span class="dead-tag">выбыл</span>'}
            ${player.left && player.alive ? '<span class="dead-tag">нет связи</span>' : ''}
          </div>
          ${role}
        </div>`;
    }).join('');
  }

  function catInline(player) {
    if (!player || !player.cat) return '';
    const cls = player.catBroken ? 'cat-inline broken' : 'cat-inline';
    const title = player.catBroken ? 'Котость треснула' : 'Котость';
    return `<img class="${cls}" src="/assets/cat-small.png" alt="котость" title="${title}">`;
  }

  function syncPromptUi() {
    const prompt = state?.prompt;
    if (!prompt) return;
    const sent = sentSig === promptSigOf(prompt);
    document.querySelectorAll('[data-target]').forEach(element => {
      const on = selection.has(element.dataset.target);
      element.classList.toggle('selected', on);
      const check = element.querySelector('.check');
      if (check) check.textContent = on ? '✓' : '';
    });
    const button = document.querySelector('[data-action="submit-prompt"]');
    if (button) button.disabled = sent || selection.size < prompt.minTargets;
    const counter = document.getElementById('selected-count');
    if (counter) counter.textContent = `Выбрано: ${selection.size} из ${prompt.maxTargets}`;
  }

  // ─────────────────────────────────────────
  //  Чат
  // ─────────────────────────────────────────

  function renderChat() {
    const dock = document.getElementById('chat-dock');
    if (!dock) return;
    if (!state || !state.canChat) {
      dock.classList.add('hidden');
      dock.classList.remove('open');
      chatOpen = false;
      unread = 0;
      return;
    }
    dock.classList.remove('hidden');

    const messages = state.chat || [];
    const sig = messages.map(m => m.id).join(',');
    const log = document.getElementById('chat-log');
    if (sig !== chatSig && log) {
      chatSig = sig;
      log.innerHTML = messages.length
        ? messages.map(chatMessageHtml).join('')
        : '<div class="chat-empty">Пока тихо. Напишите первым.</div>';
      log.scrollTop = log.scrollHeight;

      const newest = messages.length ? messages[messages.length - 1] : null;
      if (newest && newest.id > lastSeenChatId) {
        const fresh = messages.filter(m => m.id > lastSeenChatId && m.authorId !== state.you.id);
        if (!chatOpen) unread += fresh.length;
        lastSeenChatId = newest.id;
      }
    }

    const badge = document.getElementById('chat-badge');
    if (badge) {
      badge.textContent = unread > 99 ? '99+' : String(unread);
      badge.classList.toggle('hidden', unread === 0);
    }
    dock.classList.toggle('open', chatOpen);
  }

  function chatMessageHtml(message) {
    const mine = state && state.you && message.authorId === state.you.id;
    const time = new Date(message.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return `
      <div class="chat-msg ${mine ? 'mine' : ''} ${message.dead ? 'dead' : ''}">
        <div class="chat-meta">${message.dead ? '💀 ' : ''}${esc(message.authorName)} · ${time}</div>
        <div class="chat-text">${esc(message.text)}</div>
      </div>`;
  }

  async function sendChat(text) {
    if (!session || !text) return;
    try {
      await api('/api/chat', {
        method: 'POST',
        body: JSON.stringify({
          playerId: session.playerId,
          token: session.token,
          text
        })
      });
    } catch (e) {
      actionError = e.message || 'Не удалось отправить сообщение';
      render();
      return;
    }
    await poll();
  }

  function toggleChat() {
    chatOpen = !chatOpen;
    if (chatOpen) {
      unread = 0;
      const log = document.getElementById('chat-log');
      if (log) log.scrollTop = log.scrollHeight;
    }
    renderChat();
  }

  // ─────────────────────────────────────────
  //  Экраны игрока для ведущего
  // ─────────────────────────────────────────

  function activitySnapshot() {
    let screen = 'Комната';
    let detail = '';
    const prompt = state.prompt;

    if (state.phase === 'lobby') {
      screen = 'Лобби';
    } else if (state.phase === 'starting') {
      screen = 'Раздача ролей';
    } else if (prompt) {
      screen = prompt.kind === 'vote' ? 'Голосование' : 'Выбор цели';
      const names = (prompt.targets || [])
        .filter(t => selection.has(t.id))
        .map(t => t.name);
      detail = names.length ? 'Выбрано: ' + names.join(', ') : 'Ничего не выбрано';
    } else if (state.phase === 'voting') {
      screen = 'Голосование';
      detail = state.vote && state.vote.voterName
        ? 'Голосует: ' + state.vote.voterName
        : 'Ждёт голосования';
    } else if (state.phase === 'day_discussion') {
      screen = 'Обсуждение';
    } else if (state.phase === 'night_step' || state.phase === 'night_intro') {
      screen = 'Ночь';
    } else if (state.phase === 'night_results') {
      screen = 'Итоги ночи';
    } else if (state.phase === 'vote_results') {
      screen = 'Итоги голосования';
    } else if (state.phase === 'game_over') {
      screen = 'Финал';
    }

    if (!state.you.alive) detail = (detail ? detail + ' • ' : '') + 'выбыл';

    const max = Math.max(1, document.body.scrollHeight - window.innerHeight);
    const scroll = Math.round(Math.min(100, Math.max(0, (window.scrollY / max) * 100)));

    return { screen, detail, scroll };
  }

  let lastActivitySig = '';
  let lastActivityAt = 0;

  async function reportActivity() {
    if (!session || !state) return;
    const snapshot = activitySnapshot();
    const sig = snapshot.screen + '|' + snapshot.detail + '|' + snapshot.scroll;
    const now = Date.now();
    // Не дёргаем сервер (и реле) без изменений: обновляем не чаще раза в 30 с.
    if (sig === lastActivitySig && now - lastActivityAt < 30000) return;
    lastActivitySig = sig;
    lastActivityAt = now;
    try {
      await api('/api/activity', {
        method: 'POST',
        body: JSON.stringify({
          playerId: session.playerId,
          token: session.token,
          screen: snapshot.screen,
          detail: snapshot.detail,
          scroll: snapshot.scroll
        })
      });
    } catch (_) {
      lastActivitySig = '';
    }
  }

  // ─────────────────────────────────────────
  //  Действия
  // ─────────────────────────────────────────

  async function submitPrompt(skip) {
    const prompt = state?.prompt;
    if (!prompt) return;
    const sig = promptSigOf(prompt);
    if (sentSig === sig) return;

    const targets = skip ? [] : Array.from(selection);
    if (!skip && targets.length < prompt.minTargets) return;

    sentSig = sig;
    actionError = '';
    render();
    try {
      if (skip || targets.length === 0) {
        await action('skip');
      } else if (prompt.kind === 'vote') {
        await action('vote', targets[0]);
      } else if (prompt.kind === 'sniper') {
        await action('sniper', targets[0]);
      } else if (prompt.kind === 'pistol') {
        await action('pistol', targets[0]);
      } else {
        await action('night', null, targets);
      }
      await poll();
      reportActivity();
    } catch (e) {
      sentSig = '';
      actionError = e.message || 'Не удалось отправить выбор';
      render();
    }
  }

  async function action(type, targetId = null, targetIds = []) {
    if (!session) throw new Error('Нет сессии');
    const data = await api('/api/action', {
      method: 'POST',
      body: JSON.stringify({
        playerId: session.playerId,
        token: session.token,
        type,
        targetId,
        targetIds
      })
    });
    if (!data.ok) throw new Error(data.error || 'Не удалось');
    return data;
  }

  // ─────────────────────────────────────────
  //  События интерфейса
  // ─────────────────────────────────────────

  document.addEventListener('submit', event => {
    if (WATCH) {
      event.preventDefault();
      return;
    }
    if (event.target && event.target.id === 'join-form') {
      event.preventDefault();
      join();
    }
    if (event.target && event.target.id === 'chat-form') {
      event.preventDefault();
      const input = document.getElementById('chat-input');
      const text = (input?.value || '').trim();
      if (!text) return;
      if (input) input.value = '';
      sendChat(text).catch(() => {});
    }
  });

  document.addEventListener('click', event => {
    if (WATCH) return;
    const targetElement = event.target.closest('[data-target]');
    if (targetElement && state?.prompt) {
      const prompt = state.prompt;
      if (sentSig === promptSigOf(prompt)) return;
      const id = targetElement.dataset.target;
      if (prompt.maxTargets === 1) {
        selection = new Set([id]);
      } else if (selection.has(id)) {
        selection.delete(id);
      } else if (selection.size < prompt.maxTargets) {
        selection.add(id);
      }
      syncPromptUi();
      reportActivity();
      return;
    }

    const button = event.target.closest('[data-action]');
    if (!button) return;
    const name = button.dataset.action;
    if (name === 'leave') {
      leave();
    } else if (name === 'submit-prompt') {
      submitPrompt(false);
    } else if (name === 'skip-prompt') {
      submitPrompt(true);
    } else if (name === 'toggle-chat') {
      toggleChat();
    } else if (name === 'retry') {
      boot();
    }
  });

  // ─────────────────────────────────────────
  //  Звук
  // ─────────────────────────────────────────

  function ensureAudio() {
    if (!audioCtx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return null;
      try {
        audioCtx = new Ctx();
      } catch (_) {
        return null;
      }
    }
    if (audioCtx.state === 'suspended') audioCtx.resume().catch(() => {});
    return audioCtx;
  }

  document.addEventListener('pointerdown', () => {
    ensureAudio();
    unlockMp3();
  }, { passive: true });

  function noise(ctx, seconds) {
    const length = Math.max(1, Math.floor(ctx.sampleRate * seconds));
    const buffer = ctx.createBuffer(1, length, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < length; i += 1) data[i] = Math.random() * 2 - 1;
    return buffer;
  }

  function tone(ctx, type, from, to, start, length, volume) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(from, start);
    if (to !== from) osc.frequency.exponentialRampToValueAtTime(Math.max(1, to), start + length);
    gain.gain.setValueAtTime(0.0001, start);
    gain.gain.exponentialRampToValueAtTime(volume, start + Math.min(0.04, length / 3));
    gain.gain.exponentialRampToValueAtTime(0.0001, start + length);
    osc.connect(gain).connect(ctx.destination);
    osc.start(start);
    osc.stop(start + length + 0.02);
  }

  const SOUNDS = {
    shot() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      const src = ctx.createBufferSource();
      src.buffer = noise(ctx, 0.6);
      const filter = ctx.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(3200, now);
      filter.frequency.exponentialRampToValueAtTime(160, now + 0.3);
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.9, now);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.5);
      src.connect(filter).connect(gain).connect(ctx.destination);
      src.start(now);
      tone(ctx, 'sine', 170, 40, now, 0.32, 0.8);
    },
    meow() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(520, now);
      osc.frequency.linearRampToValueAtTime(820, now + 0.2);
      osc.frequency.exponentialRampToValueAtTime(340, now + 0.62);
      const filter = ctx.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.value = 1500;
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.exponentialRampToValueAtTime(0.32, now + 0.07);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.66);
      osc.connect(filter).connect(gain).connect(ctx.destination);
      osc.start(now);
      osc.stop(now + 0.7);
      const lfo = ctx.createOscillator();
      lfo.frequency.value = 24;
      const lfoGain = ctx.createGain();
      lfoGain.gain.value = 22;
      lfo.connect(lfoGain).connect(osc.frequency);
      lfo.start(now);
      lfo.stop(now + 0.7);
    },
    gong() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      tone(ctx, 'sine', 196, 190, now, 1.8, 0.45);
      tone(ctx, 'sine', 294, 288, now + 0.01, 1.5, 0.28);
      tone(ctx, 'triangle', 98, 96, now, 1.9, 0.3);
    },
    heart() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      tone(ctx, 'sine', 72, 46, now, 0.22, 0.7);
      tone(ctx, 'sine', 66, 42, now + 0.3, 0.3, 0.55);
    },
    boom() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      const src = ctx.createBufferSource();
      src.buffer = noise(ctx, 1.2);
      const filter = ctx.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(900, now);
      filter.frequency.exponentialRampToValueAtTime(70, now + 0.9);
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.95, now);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 1.1);
      src.connect(filter).connect(gain).connect(ctx.destination);
      src.start(now);
      tone(ctx, 'sine', 90, 28, now, 0.9, 0.8);
    },
    bell() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      tone(ctx, 'sine', 880, 880, now, 1.1, 0.25);
      tone(ctx, 'sine', 1320, 1320, now + 0.02, 0.9, 0.14);
      tone(ctx, 'sine', 1760, 1760, now + 0.03, 0.7, 0.09);
    },
    shatter() {
      const ctx = ensureAudio();
      if (!ctx) return;
      const now = ctx.currentTime;
      const src = ctx.createBufferSource();
      src.buffer = noise(ctx, 0.7);
      const filter = ctx.createBiquadFilter();
      filter.type = 'highpass';
      filter.frequency.value = 1800;
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.45, now);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.6);
      src.connect(filter).connect(gain).connect(ctx.destination);
      src.start(now);
    }
  };

  function playSoundKind(kind) {
    if (WATCH) return;
    const player = SOUNDS[kind];
    if (!player) return;
    try {
      player();
    } catch (_) {}
  }

  /** Играет звук от ведущего: встроенный синтез или загруженный mp3. */
  function playSoundCommand(sound) {
    if (!sound) return;
    if (sound.src) {
      playMp3(sound.src);
      return;
    }
    playSoundKind(sound.kind);
  }

  function ensureMp3Player() {
    if (!mp3Player) {
      mp3Player = new Audio();
      mp3Player.preload = 'auto';
    }
    return mp3Player;
  }

  function unlockMp3() {
    if (mp3Unlocked) return;
    try {
      const el = ensureMp3Player();
      el.src = SILENT_WAV;
      el.play().then(() => {
        el.pause();
        el.currentTime = 0;
        mp3Unlocked = true;
      }).catch(() => {});
    } catch (_) {}
  }

  function playMp3(src) {
    try {
      const el = ensureMp3Player();
      el.src = src;
      el.play().catch(() => showAudioHint());
    } catch (_) {
      showAudioHint();
    }
  }

  function showAudioHint() {
    if (document.getElementById('audio-hint')) return;
    const hint = document.createElement('button');
    hint.id = 'audio-hint';
    hint.type = 'button';
    hint.className = 'audio-hint';
    hint.textContent = '🔊 Нажмите, чтобы включить звук';
    hint.addEventListener('click', () => {
      ensureAudio();
      unlockMp3();
      hint.remove();
    });
    document.body.appendChild(hint);
  }

  // ─────────────────────────────────────────
  //  Анимации: роль и котость
  // ─────────────────────────────────────────

  function handleEffects(snapshot) {
    if (!snapshot) return;
    if (snapshot.phase === 'lobby') {
      roleCinemaShown = false;
    }
    if (!WATCH && snapshot.sound && snapshot.sound.seq > lastSoundSeq) {
      lastSoundSeq = snapshot.sound.seq;
      playSoundCommand(snapshot.sound);
    }
    if (snapshot.catSave && snapshot.catSave.seq > lastCatSeq) {
      lastCatSeq = snapshot.catSave.seq;
      showCatSave(snapshot.catSave);
    }
    if (!roleCinemaShown &&
        snapshot.phase === 'starting' &&
        snapshot.you && snapshot.you.roleTitle) {
      roleCinemaShown = true;
      showRoleCinema(snapshot.you);
    }
  }

  function cinemaShards(count) {
    let html = '';
    for (let i = 0; i < count; i += 1) {
      const angle = Math.round((360 / count) * i + (i % 3) * 9);
      const dist = 66 + (i % 4) * 24;
      html += `<i class="shard s${i % 6}" style="--a:${angle}deg;--d:${dist}px"></i>`;
    }
    return html;
  }

  function showRoleCinema(role) {
    if (document.getElementById('role-cinema')) return;
    ensureAudio();
    const overlay = document.createElement('div');
    overlay.id = 'role-cinema';
    overlay.className = 'role-cinema';
    overlay.innerHTML = `
      <div class="cinema-flash"></div>
      <div class="cinema-gun">🔫</div>
      <div class="cinema-bullet"></div>
      <div class="cinema-burst">${cinemaShards(18)}</div>
      <div class="cinema-role">
        <div class="cinema-icon">${esc(role.roleIcon || '❔')}</div>
        <div class="cinema-title">${esc(role.roleTitle || '')}</div>
        <div class="cinema-sub">${esc(role.roleDescription || '')}</div>
      </div>
      <div class="cinema-hint">нажмите, чтобы продолжить</div>`;
    document.body.appendChild(overlay);

    playSoundKind('shot');
    setTimeout(() => playSoundKind('shatter'), 520);
    setTimeout(() => {
      overlay.classList.add('revealed');
      playSoundKind('bell');
    }, 1080);

    let closed = false;
    const close = () => {
      if (closed) return;
      closed = true;
      overlay.classList.add('closing');
      setTimeout(() => overlay.remove(), 420);
    };
    overlay.addEventListener('click', close);
    setTimeout(close, 7000);
  }

  function showCatSave(event) {
    if (document.getElementById('cat-cinema')) return;
    ensureAudio();
    const overlay = document.createElement('div');
    overlay.id = 'cat-cinema';
    overlay.className = 'cat-cinema';
    const drift = [
      ['-30vw', '-26vh', '-22deg'],
      ['26vw', '-32vh', '18deg'],
      ['36vw', '10vh', '26deg'],
      ['-34vw', '18vh', '-16deg'],
      ['-6vw', '34vh', '12deg'],
      ['10vw', '-16vh', '-28deg']
    ];
    const shards = drift.map(([tx, ty, rot], i) =>
      `<i class="cat-shard s${i}" style="--tx:${tx};--ty:${ty};--rot:${rot}"></i>`
    ).join('');
    overlay.innerHTML = `
      <div class="cat-stage">
        <img class="cat-photo" src="/assets/cat.png" alt="">
        <svg class="cat-cracks" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
          <path d="M50 4 L44 34 L55 48 L46 66 L52 96" />
          <path d="M44 34 L18 26 L6 40" />
          <path d="M55 48 L82 36 L96 44" />
          <path d="M46 66 L22 80 L10 76" />
          <path d="M52 96 L78 82 L92 88" />
          <path d="M44 34 L60 20" />
        </svg>
      </div>
      <div class="cat-shards">${shards}</div>
      <div class="cat-caption">
        <div class="cat-caption-main">Его защитила котость</div>
        <div class="cat-caption-name">${esc(event.playerName || '')}</div>
      </div>`;
    document.body.appendChild(overlay);

    playSoundKind('meow');
    setTimeout(() => overlay.classList.add('cracking'), 850);
    setTimeout(() => {
      overlay.classList.add('shattered');
      playSoundKind('shatter');
    }, 1650);
    setTimeout(() => overlay.classList.add('closing'), 4300);
    setTimeout(() => overlay.remove(), 4900);
  }

  // ─────────────────────────────────────────
  //  Таймеры и утилиты
  // ─────────────────────────────────────────

  function updateCountdowns() {
    const now = Date.now() + serverOffset;
    document.querySelectorAll('[data-countdown]').forEach(element => {
      const ends = Number(element.dataset.ends || 0);
      if (!ends) {
        element.textContent = '';
        return;
      }
      const left = Math.max(0, Math.ceil((ends - now) / 1000));
      element.textContent = `⏳ Осталось: ${left} с`;
    });
    document.querySelectorAll('[data-progress]').forEach(element => {
      const parts = (element.dataset.progress || '').split(',').map(Number);
      const done = parts[0] || 0;
      const total = parts[1] || 0;
      const bar = element.querySelector('i');
      if (bar && total > 0) bar.style.width = Math.min(100, (done / total) * 100) + '%';
    });
  }

  function stateSig(snapshot) {
    const copy = { ...snapshot };
    copy.serverNow = 0;
    copy.chat = [];
    return JSON.stringify(copy);
  }

  function promptSigOf(prompt) {
    if (!prompt) return '';
    return [
      prompt.kind,
      prompt.title,
      prompt.endsAt,
      (prompt.targets || []).map(target => target.id).join(',')
    ].join('|');
  }

  function winnerText(winner) {
    switch (winner) {
      case 'mafia': return '🔴 Мафия победила!';
      case 'maniac': return '🔪 Маньяк победил!';
      case 'jester': return '🃏 Шут победил!';
      case 'peaceful': return '✅ Мирные победили!';
      default: return 'Игра окончена';
    }
  }

  function deviceIcon(kind) {
    const common = 'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';
    if (kind === 'ios') {
      return `<svg ${common}><rect x="6" y="2.5" width="12" height="19" rx="3.2"/><path d="M9.2 5h5.6"/><circle cx="12" cy="18.2" r="0.8" fill="currentColor" stroke="none"/></svg>`;
    }
    if (kind === 'android') {
      return `<svg ${common}><rect x="6" y="2.5" width="12" height="19" rx="3.2"/><path d="M9 5h6"/><circle cx="9" cy="18.3" r="0.7" fill="currentColor" stroke="none"/><circle cx="12" cy="18.3" r="0.7" fill="currentColor" stroke="none"/><circle cx="15" cy="18.3" r="0.7" fill="currentColor" stroke="none"/></svg>`;
    }
    return `<svg ${common}><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>`;
  }

  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, char => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }[char]));
  }

  function fatal(message) {
    app.innerHTML = `
      <div class="screen">
        <div class="brand">
          <div class="mask">🎭</div>
          <h1>МАФИЯ</h1>
        </div>
        <div class="banner error">${esc(message)}</div>
        <button class="btn primary" data-action="retry">Повторить</button>
      </div>`;
  }

  setInterval(updateCountdowns, 250);
  boot();
})();
