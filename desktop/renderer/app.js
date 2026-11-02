'use strict';

// ───────────────────────── данные ─────────────────────────

const ITEMS = [
  ['COBBLESTONE', 'Булыжник', '#8A8F99'],
  ['COAL', 'Уголь', '#4A4F59'],
  ['IRON_INGOT', 'Железо', '#D8C2A8'],
  ['GOLD_INGOT', 'Золото', '#F2C94C'],
  ['DIAMOND', 'Алмазы', '#5CE1E6'],
  ['INK_SACK:4', 'Лазурит', '#3D6BE0'],
  ['EMERALD', 'Изумруды', '#2ECC71'],
  ['REDSTONE', 'Редстоун', '#E5383B'],
  ['QUARTZ', 'Кварц', '#EDE6DA'],
  ['OBSIDIAN', 'Обсидиан', '#6A3FA0'],
  ['GLOWSTONE_DUST', 'Светокамень', '#F7D774'],
  ['GRAVEL', 'Гравий', '#8C8580'],
  ['ICE', 'Лёд', '#9CC9F5'],
  ['NETHERRACK', 'Незерак', '#A04848'],
  ['SAND', 'Песок', '#E3D7A3'],
  ['SAND:1', 'Красный песок', '#C8743A'],
  ['ENDER_STONE', 'Эндерняк', '#E6E2A1'],
  ['MYCEL', 'Мицелий', '#9E7F9F'],
  ['SULPHUR', 'Сера', '#D9D35B'],
  ['MITHRIL_ORE', 'Мифрил', '#6FD3C9'],
  ['HARD_STONE', 'Твёрдый камень', '#6E7480'],
  ['GEMSTONE_COLLECTION', 'Самоцветы', '#E05AA6'],
  ['GLACITE', 'Глацит', '#B5E3F2'],
  ['UMBER', 'Умбра', '#9B6A3B'],
  ['TUNGSTEN', 'Вольфрам', '#8A95A0'],
].map(([key, title, color]) => ({ key, title, color }));
const ITEM_KEYS = new Set(ITEMS.map((i) => i.key));
const MAX_SNAPSHOTS = 1000;
const AUTO_INTERVAL = 5 * 60 * 1000;

const LS = {
  get(k, def) {
    try { const v = localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch { return def; }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* нет места или запрещено */ }
  },
};

const settings = LS.get('settings', {});
const saveSettings = () => LS.set('settings', settings);

// ───────────────────────── API ─────────────────────────

class ApiError extends Error {}

async function httpGet(url, headers) {
  if (!window.native) throw new ApiError('Приложение запущено не через Electron: запросы недоступны.');
  const r = await window.native.get(url, headers);
  if (!r || r.status === 0) throw new ApiError('Нет соединения с сервером. Проверь интернет.');
  return r;
}

async function lookupUuid(nick) {
  const r = await httpGet('https://api.mojang.com/users/profiles/minecraft/' + encodeURIComponent(nick));
  if (r.status === 204 || r.status === 404 || !r.body.trim()) {
    throw new ApiError(`Игрок «${nick}» не найден. Проверь ник.`);
  }
  if (r.status !== 200) throw new ApiError(`Сервис Mojang ответил ошибкой ${r.status}. Попробуй позже.`);
  const o = JSON.parse(r.body);
  return { uuid: o.id, name: o.name || nick };
}

async function loadProfiles(uuid, key) {
  const r = await httpGet(`https://api.hypixel.net/v2/skyblock/profiles?uuid=${uuid}`, { 'API-Key': key });
  if (r.status === 403) {
    throw new ApiError('Hypixel не принял API-ключ. Получи новый на developer.hypixel.net и вставь его в настройках.');
  }
  if (r.status === 429) throw new ApiError('Слишком много запросов. Подожди пару минут и обнови снова.');
  if (r.status !== 200) throw new ApiError(`Hypixel ответил ошибкой ${r.status}. Попробуй позже.`);

  const root = JSON.parse(r.body);
  if (!root.success) throw new ApiError(root.cause || 'Hypixel вернул ошибку.');
  const arr = root.profiles || [];
  if (!arr.length) throw new ApiError('У этого игрока нет профилей SkyBlock.');

  return arr.map((p, i) => {
    const m = p.members && p.members[uuid];
    let collections = null;
    if (m && m.collection) {
      collections = {};
      for (const [k, v] of Object.entries(m.collection)) {
        if (ITEM_KEYS.has(k)) collections[k] = Number(v) || 0;
      }
    }
    const powders = [];
    const mc = m && m.mining_core;
    if (mc) {
      for (const [k, title] of [
        ['mithril', 'Мифриловый порошок'],
        ['gemstone', 'Самоцветный порошок'],
        ['glacite', 'Глацитовый порошок'],
      ]) {
        const cur = mc['powder_' + k];
        if (typeof cur === 'number') {
          const t = mc[`powder_${k}_total`];
          const total = typeof t === 'number' ? t : cur + (mc['powder_spent_' + k] || 0);
          powders.push({ title, current: cur, total: Math.max(total, cur) });
        }
      }
    }
    return {
      id: p.profile_id,
      name: p.cute_name || `Профиль ${i + 1}`,
      selected: !!p.selected,
      collections,
      powders,
    };
  });
}

// ───────────────────────── история ─────────────────────────

const hKey = (u, p) => `h_${u}_${p}`;
const cKey = (u, p) => `checked_${u}_${p}`;
const loadHistory = (u, p) => LS.get(hKey(u, p), []);
const totalOf = (s) => Object.values(s.c).reduce((a, b) => a + b, 0);

function sameCounts(a, b) {
  const ka = Object.keys(a);
  return ka.length === Object.keys(b).length && ka.every((k) => a[k] === b[k]);
}

/** Добавляет снимок, если цифры изменились. Возвращает историю. */
function addSnapshot(u, p, counts, now) {
  const h = loadHistory(u, p);
  const last = h[h.length - 1];
  if (!last || !sameCounts(last.c, counts)) {
    h.push({ t: now, c: counts });
    while (h.length > MAX_SNAPSHOTS) h.shift();
    LS.set(hKey(u, p), h);
  }
  LS.set(cKey(u, p), now);
  return h;
}

/** Последний снимок до момента since, иначе самый первый. */
function baseline(h, since) {
  let b = null;
  for (const s of h) if (s.t <= since) b = s;
  return b || h[0] || null;
}

function startOfDay(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function plural(n) {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return 'коллекция';
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return 'коллекции';
  return 'коллекций';
}

// ───────────────────────── интерфейс ─────────────────────────

const $ = (id) => document.getElementById(id);
const el = {
  subtitle: $('subtitle'), profile: $('profile'), auto: $('auto'), refresh: $('refresh'),
  toggleSettings: $('toggleSettings'), settings: $('settings'), form: $('settingsForm'),
  nick: $('nick'), key: $('key'), status: $('status'), data: $('data'),
  summaryCard: $('summaryCard'), total: $('total'), today: $('today'), week: $('week'),
  chartCard: $('chartCard'), chartTitle: $('chartTitle'), chartReset: $('chartReset'),
  resCard: $('resCard'), resList: $('resList'), zeroNote: $('zeroNote'),
  powderCard: $('powderCard'), powderList: $('powderList'), foot: $('foot'),
};
const chart = new MinerChart($('chart'));

const state = {
  uuid: '',
  profiles: [],
  loading: false,
  chartKey: null,
  chartRange: LS.get('chartRange', 0),
  history: [],
  checked: 0,
  timer: null,
};

function showStatus(message, kind = 'info') {
  el.status.textContent = message;
  el.status.className = 'status' + (kind === 'info' ? '' : ' ' + kind);
  el.status.hidden = false;
}

function setLoading(on) {
  state.loading = on;
  el.refresh.disabled = on;
  el.refresh.textContent = on ? 'Загрузка…' : 'Обновить';
}

async function refresh() {
  if (state.loading) return;
  const nick = (settings.nick || '').trim();
  const key = (settings.key || '').trim();
  if (!nick || !key) {
    el.settings.hidden = false;
    showStatus('Введи свой ник и API-ключ Hypixel, чтобы начать.');
    return;
  }
  setLoading(true);
  if (el.data.hidden) showStatus('Загружаю данные с Hypixel…');

  try {
    let uuid = settings.uuid;
    let realName = settings.realName || nick;
    if (!uuid || (settings.uuidFor || '').toLowerCase() !== nick.toLowerCase()) {
      const res = await lookupUuid(nick);
      uuid = res.uuid;
      realName = res.name;
      Object.assign(settings, { uuid, uuidFor: nick, realName });
      delete settings.profileId;
      saveSettings();
    }
    const profiles = await loadProfiles(uuid, key);
    state.uuid = uuid;
    state.profiles = profiles;
    el.subtitle.textContent = `${realName} · SkyBlock`;

    let index = profiles.findIndex((p) => p.id === settings.profileId);
    if (index < 0) index = profiles.findIndex((p) => p.selected);
    if (index < 0) index = 0;

    el.profile.replaceChildren(...profiles.map((p, i) => {
      const o = document.createElement('option');
      o.value = String(i);
      o.textContent = p.name + (p.selected ? ' (активный)' : '');
      return o;
    }));
    el.profile.value = String(index);
    el.profile.hidden = profiles.length < 2;

    showProfile(profiles[index]);
  } catch (err) {
    showStatus(err instanceof ApiError ? err.message : 'Не удалось разобрать ответ сервера: ' + err.message, 'error');
  } finally {
    setLoading(false);
  }
}

function showProfile(p) {
  settings.profileId = p.id;
  saveSettings();
  el.data.hidden = false;
  renderPowder(p);

  if (!p.collections) {
    showStatus(
      'Коллекции этого профиля скрыты. В игре открой SkyBlock Menu → Settings → API Settings ' +
      'и включи «Collections». Через пару минут нажми «Обновить».',
      'warn'
    );
    el.summaryCard.hidden = el.chartCard.hidden = el.resCard.hidden = true;
    el.foot.hidden = true;
    return;
  }
  el.status.hidden = true;
  el.summaryCard.hidden = el.chartCard.hidden = el.resCard.hidden = false;

  const now = Date.now();
  const full = {};
  for (const it of ITEMS) full[it.key] = p.collections[it.key] || 0;
  const history = addSnapshot(state.uuid, p.id, full, now);
  state.history = history;
  state.checked = now;

  const total = Object.values(full).reduce((a, b) => a + b, 0);
  const today = baseline(history, startOfDay(0));
  const week = baseline(history, startOfDay(-6));
  const dToday = total - (today ? totalOf(today) : total);
  const dWeek = total - (week ? totalOf(week) : total);

  el.total.textContent = Fmt.num(total);
  setChip(el.today, `${Fmt.delta(dToday)} сегодня`, dToday > 0);
  setChip(el.week, `${Fmt.delta(dWeek)} за 7 дней`, dWeek > 0);

  // ── ресурсы
  const rows = ITEMS.map((it) => ({ it, value: full[it.key] })).sort((a, b) => b.value - a.value);
  const nonZero = rows.filter((r) => r.value > 0);
  const max = Math.max(1, nonZero.length ? nonZero[0].value : 1);
  el.resList.replaceChildren(...nonZero.map(({ it, value }) => {
    const d = value - (today && today.c[it.key] != null ? today.c[it.key] : value);
    return resourceRow(it, value, d, max);
  }));
  const zeros = rows.length - nonZero.length;
  if (!nonZero.length) {
    el.zeroNote.textContent = 'Пока ничего не добыто.';
    el.zeroNote.hidden = false;
  } else if (zeros > 0) {
    el.zeroNote.textContent = `Ещё ${zeros} ${plural(zeros)} пока на нуле`;
    el.zeroNote.hidden = false;
  } else {
    el.zeroNote.hidden = true;
  }

  el.foot.textContent =
    `Проверено: ${Fmt.full(now)} · снимков в истории: ${history.length}. ` +
    'Числа — это коллекции SkyBlock: сколько предметов собрано добычей. Hypixel обновляет их с задержкой в несколько минут.';
  el.foot.hidden = false;

  updateChart();
}

function setChip(node, text, up) {
  node.textContent = text;
  node.classList.toggle('up', up);
}

function resourceRow(it, value, delta, max) {
  const li = document.createElement('li');
  const btn = document.createElement('button');
  btn.className = 'res';
  btn.dataset.key = it.key;
  btn.title = `Показать график: ${it.title}`;

  const top = document.createElement('div');
  top.className = 'res-top';
  const sw = document.createElement('span');
  sw.className = 'swatch';
  sw.style.background = it.color;
  const name = document.createElement('span');
  name.className = 'res-name';
  name.textContent = it.title;
  top.append(sw, name);
  if (delta !== 0) {
    const dl = document.createElement('span');
    dl.className = 'res-delta' + (delta < 0 ? ' down' : '');
    dl.textContent = Fmt.delta(delta);
    top.append(dl);
  }
  const val = document.createElement('span');
  val.className = 'res-val';
  val.textContent = Fmt.num(value);
  top.append(val);

  const bar = document.createElement('div');
  bar.className = 'bar';
  const fill = document.createElement('i');
  fill.style.width = (Math.min(1, Math.max(0.02, value / max)) * 100).toFixed(1) + '%';
  fill.style.background = it.color;
  bar.append(fill);

  btn.append(top, bar);
  btn.addEventListener('click', () => {
    state.chartKey = state.chartKey === it.key ? null : it.key;
    updateChart();
    if (window.matchMedia('(max-width: 900px)').matches) {
      el.chartCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });
  li.append(btn);
  return li;
}

function renderPowder(p) {
  el.powderCard.hidden = !p.powders.length;
  el.powderList.replaceChildren(...p.powders.map((pw) => {
    const li = document.createElement('li');
    li.className = 'powder';
    const name = document.createElement('span');
    name.textContent = pw.title;
    const right = document.createElement('div');
    const b = document.createElement('b');
    b.textContent = Fmt.num(pw.current);
    const s = document.createElement('small');
    s.textContent = `всего ${Fmt.num(pw.total)}`;
    right.append(b, s);
    li.append(name, right);
    return li;
  }));
}

function updateChart() {
  const item = ITEMS.find((i) => i.key === state.chartKey) || null;
  const series = state.history.map((s) => [s.t, item ? s.c[item.key] || 0 : totalOf(s)]);
  // после последнего изменения значение держалось до последней проверки
  if (series.length && state.checked > series[series.length - 1][0]) {
    series.push([state.checked, series[series.length - 1][1]]);
  }
  const from = state.chartRange ? Date.now() - state.chartRange : null;
  chart.setData(series, item ? item.color : getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(), from);

  el.chartTitle.textContent = 'График · ' + (item ? item.title : 'всего добыто');
  el.chartTitle.style.color = item ? item.color : '';
  el.chartReset.hidden = !item;
  document.querySelectorAll('.range').forEach((b) => {
    b.classList.toggle('on', Number(b.dataset.range) === state.chartRange);
  });
  document.querySelectorAll('.res').forEach((b) => {
    b.classList.toggle('on', b.dataset.key === state.chartKey);
  });
}

function setAuto(on) {
  settings.auto = on;
  saveSettings();
  clearInterval(state.timer);
  state.timer = on ? setInterval(() => { if (!document.hidden) refresh(); }, AUTO_INTERVAL) : null;
}

// ───────────────────────── события ─────────────────────────

el.refresh.addEventListener('click', refresh);
el.toggleSettings.addEventListener('click', () => {
  el.settings.hidden = !el.settings.hidden;
  if (!el.settings.hidden) el.nick.focus();
});
el.form.addEventListener('submit', (e) => {
  e.preventDefault();
  settings.nick = el.nick.value.trim();
  settings.key = el.key.value.trim();
  saveSettings();
  el.settings.hidden = true;
  refresh();
});
el.profile.addEventListener('change', () => {
  const p = state.profiles[Number(el.profile.value)];
  if (p) showProfile(p);
});
el.auto.addEventListener('change', () => setAuto(el.auto.checked));
el.chartReset.addEventListener('click', () => { state.chartKey = null; updateChart(); });
document.querySelectorAll('.range').forEach((b) => {
  b.addEventListener('click', () => {
    state.chartRange = Number(b.dataset.range);
    LS.set('chartRange', state.chartRange);
    updateChart();
  });
});
window.addEventListener('keydown', (e) => {
  if (e.key === 'F5' || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'r')) {
    e.preventDefault();
    refresh();
  }
});

// ───────────────────────── запуск ─────────────────────────

el.nick.value = settings.nick || '';
el.key.value = settings.key || '';
el.auto.checked = !!settings.auto;
if (settings.realName) el.subtitle.textContent = `${settings.realName} · SkyBlock`;
setAuto(!!settings.auto);
updateChart();

if (!settings.nick || !settings.key) {
  el.settings.hidden = false;
  showStatus('Введи свой ник и API-ключ Hypixel, чтобы начать.');
  el.nick.focus();
} else {
  refresh();
}
