'use strict';
/* MGP Admin — веб-панель для настройки режимов, карт и общего конфига. Без зависимостей. */

// ------------------------------------------------------------------ состояние и API
const S = { token: null, meta: null, modes: [], maps: [], dirty: false, timer: null };

function store(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }
function load(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

(function grabToken() {
  const m = location.hash.match(/token=([0-9a-fA-F]+)/);
  if (m) { store('mgp_token', m[1]); history.replaceState(null, '', location.pathname + '#/overview'); }
  S.token = load('mgp_token');
})();

async function api(method, path, body) {
  const r = await fetch('/api/' + path, {
    method, headers: { 'X-Token': S.token || '', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  let j = {};
  try { j = await r.json(); } catch (e) { j = { error: 'Сервер не ответил' }; }
  if (r.status === 401) { showLogin(j.error); throw new Error(j.error); }
  if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
  return j;
}

// ------------------------------------------------------------------ DOM-помощники
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c.nodeType ? c : String(c));
  return el;
}
const $ = (sel) => document.querySelector(sel);
/** Заменить содержимое элемента (массивы разворачиваются, null пропускается). */
function put(el, ...kids) {
  el.replaceChildren(...kids.flat(Infinity).filter((c) => c != null && c !== false).map((c) => (c.nodeType ? c : String(c))));
}
function toast(msg, err) {
  const d = h('div', { class: err ? 'err' : '' }, msg);
  $('#toast').append(d);
  setTimeout(() => d.remove(), err ? 7000 : 2600);
}
function markDirty() {
  if (!S.dirty) { S.dirty = true; const b = $('#save-btn'); if (b) b.disabled = false; const st = $('#save-status'); if (st) st.textContent = 'Есть несохранённые изменения'; }
}
function clearDirty() {
  S.dirty = false; const b = $('#save-btn'); if (b) b.disabled = true; const st = $('#save-status'); if (st) st.textContent = 'Сохранено';
}
const clone = (o) => JSON.parse(JSON.stringify(o));

// ------------------------------------------------------------------ предпросмотр MiniMessage
const MM_COLORS = { black: '#000000', dark_blue: '#0000AA', dark_green: '#00AA00', dark_aqua: '#00AAAA', dark_red: '#AA0000',
  dark_purple: '#AA00AA', gold: '#FFAA00', gray: '#AAAAAA', grey: '#AAAAAA', dark_gray: '#555555', dark_grey: '#555555', blue: '#5555FF',
  green: '#55FF55', aqua: '#55FFFF', red: '#FF5555', light_purple: '#FF55FF', yellow: '#FFFF55', white: '#FFFFFF' };
const MM_DECOR = { bold: 'b', b: 'b', italic: 'i', i: 'i', em: 'i', underlined: 'u', u: 'u', strikethrough: 's', st: 's', obfuscated: 'o', obf: 'o' };
function mmPreview(src) {
  const out = h('span');
  let st = { color: '#FFFFFF', b: false, i: false, u: false, s: false };
  const colorStack = [];
  const re = /<(\/?)([#!a-zA-Z0-9_:]+)(?::[^>]*)?>|\{([a-z0-9_]+)\}/g;
  let last = 0, m;
  const push = (text, ph) => {
    if (!text) return;
    const sp = h('span', { class: ph ? 'ph' : null }, text);
    sp.style.color = st.color;
    if (st.b) sp.style.fontWeight = '700';
    if (st.i) sp.style.fontStyle = 'italic';
    const deco = [st.u && 'underline', st.s && 'line-through'].filter(Boolean).join(' ');
    if (deco) sp.style.textDecoration = deco;
    out.append(sp);
  };
  src = String(src ?? '');
  while ((m = re.exec(src))) {
    push(src.slice(last, m.index));
    last = re.lastIndex;
    if (m[3]) { push('{' + m[3] + '}', true); continue; }
    const close = m[1] === '/', tag = m[2].toLowerCase();
    if (tag === 'reset') { st = { color: '#FFFFFF', b: false, i: false, u: false, s: false }; colorStack.length = 0; continue; }
    if (tag === 'newline' || tag === 'br') { out.append(h('br')); continue; }
    const color = tag.startsWith('#') ? tag : MM_COLORS[tag];
    if (color) {
      if (close) st.color = colorStack.pop() || '#FFFFFF';
      else { colorStack.push(st.color); st.color = color; }
      continue;
    }
    if (MM_DECOR[tag]) { st[MM_DECOR[tag]] = !close; continue; }
    if (tag.startsWith('!')) { const d = MM_DECOR[tag.slice(1)]; if (d) st[d] = false; continue; }
    push(m[0]); // неизвестный тег показываем как есть
  }
  push(src.slice(last));
  return out;
}
function mmText(src) { // текст без тегов (для списков)
  return String(src ?? '').replace(/<[^>]+>/g, '');
}

// ------------------------------------------------------------------ поля форм
// Схема поля: { k: ключ, l: подпись, t: тип, hint, opts, list (datalist), wide }
function getPath(obj, k) { return k.split('.').reduce((o, p) => (o == null ? undefined : o[p]), obj); }
function setPath(obj, k, v) {
  const parts = k.split('.');
  let o = obj;
  for (let i = 0; i < parts.length - 1; i++) { if (typeof o[parts[i]] !== 'object' || o[parts[i]] === null || Array.isArray(o[parts[i]])) o[parts[i]] = {}; o = o[parts[i]]; }
  const last = parts[parts.length - 1];
  if (v === undefined) delete o[last]; else o[last] = v;
}

function fieldWrap(f, input, extra) {
  return h('div', { class: 'field' + (f.wide ? ' wide' : '') }, h('label', null, f.l), input, extra, f.hint ? h('div', { class: 'hint' }, f.hint) : null);
}

function renderField(obj, f) {
  const cur = getPath(obj, f.k);
  const set = (v) => { setPath(obj, f.k, v); markDirty(); };
  switch (f.t) {
    case 'bool': {
      const cb = h('input', { type: 'checkbox', checked: cur === true || cur === 'true', onchange: (e) => set(e.target.checked) });
      return h('div', { class: 'field check' + (f.wide ? ' wide' : '') }, cb, h('label', null, f.l), f.hint ? h('span', { class: 'hint' }, f.hint) : null);
    }
    case 'int': case 'num':
      return fieldWrap(f, h('input', { type: 'number', step: f.t === 'int' ? '1' : 'any', value: cur ?? '', placeholder: f.ph ?? '',
        oninput: (e) => { const v = e.target.value.trim(); set(v === '' ? undefined : (f.t === 'int' ? parseInt(v, 10) : parseFloat(v))); } }));
    case 'select': {
      const opts = typeof f.opts === 'function' ? f.opts() : f.opts;
      const sel = h('select', { onchange: (e) => set(e.target.value === '' ? undefined : e.target.value) },
        f.empty ? h('option', { value: '' }, f.empty) : null,
        opts.map((o) => h('option', { value: o }, (f.labels && f.labels[o]) || o)));
      sel.value = cur ?? '';
      if (cur != null && !opts.includes(String(cur))) sel.prepend(h('option', { value: cur }, cur));
      sel.value = cur ?? '';
      return fieldWrap(f, sel);
    }
    case 'time':
      return fieldWrap(f, h('input', { value: cur ?? '', placeholder: f.ph ?? 'например 5:00 или 300',
        oninput: (e) => { const v = e.target.value.trim(); set(v === '' ? undefined : (/^\d+$/.test(v) ? parseInt(v, 10) : v)); } }));
    case 'lines': {
      const ta = h('textarea', { rows: f.rows || Math.min(12, Math.max(3, (cur || []).length + 1)), placeholder: f.ph ?? 'по одному на строку',
        oninput: (e) => {
          let arr = e.target.value.split('\n');
          if (!f.keepEmpty) arr = arr.map((x) => x.trim()).filter((x) => x !== '');
          else while (arr.length && arr[arr.length - 1] === '') arr.pop();
          set(arr);
          if (prev) renderLinesPreview(prev, arr);
        } }, (cur || []).join('\n'));
      const prev = f.preview ? h('div', { class: 'preview' }) : null;
      if (prev) renderLinesPreview(prev, cur || []);
      return fieldWrap({ ...f, wide: f.wide !== false }, ta, prev);
    }
    case 'mm': {
      const prev = h('div', { class: 'preview' }, mmPreview(cur));
      return fieldWrap(f, h('input', { value: cur ?? '', placeholder: f.ph ?? '',
        oninput: (e) => { set(e.target.value === '' && f.optional ? undefined : e.target.value); put(prev, mmPreview(e.target.value)); } }), prev);
    }
    default: // text, material и т.п.
      return fieldWrap(f, h('input', { value: cur ?? '', list: f.list || (f.t === 'material' ? 'dl-materials' : null), placeholder: f.ph ?? '',
        oninput: (e) => { const v = e.target.value; set(v === '' && f.optional ? undefined : (f.t === 'material' ? v.toUpperCase() : v)); } }));
  }
}
function renderLinesPreview(el, arr) {
  if (!arr.length) return put(el);
  put(el, ...arr.map((l, i) => [i ? h('br') : null, mmPreview(l)]).flat());
}

/** Сетка обычных полей. */
function fields(obj, list) {
  return h('div', { class: 'grid' }, list.map((f) => renderField(obj, f)));
}

// ------------------------------------------------------------------ предметы
function parseItem(v) {
  if (v && typeof v === 'object') return clone(v);
  const p = String(v ?? '').trim().split(/\s+/);
  return { material: (p[0] || 'STONE').toUpperCase(), amount: p[1] ? parseInt(p[1], 10) : 1 };
}
function packItem(o) {
  const extra = Object.keys(o).filter((k) => !['material', 'amount'].includes(k) && o[k] !== undefined && o[k] !== '' &&
    !(Array.isArray(o[k]) && !o[k].length) && !(typeof o[k] === 'object' && !Array.isArray(o[k]) && !Object.keys(o[k]).length));
  if (!extra.length) return (o.amount && o.amount !== 1) ? `${o.material} ${o.amount}` : o.material;
  const out = {}; for (const k of Object.keys(o)) if (o[k] !== undefined && o[k] !== '') out[k] = o[k];
  return out;
}
function enchToText(e) { return e ? Object.entries(e).map(([k, v]) => `${k}=${v}`).join(', ') : ''; }
function textToEnch(t) {
  const out = {};
  for (const part of t.split(',')) { const [k, v] = part.split('=').map((x) => (x || '').trim()); if (k) out[k.toLowerCase()] = parseInt(v || '1', 10) || 1; }
  return out;
}
/** Редактор одного предмета: holder[key] — строка "MATERIAL N" или объект. */
function itemEditor(holder, key, opts = {}) {
  const it = parseItem(holder[key]);
  const commit = () => { holder[key] = packItem(it); markDirty(); };
  const more = h('div', { class: 'more', hidden: !(it.name || it.enchants || it.slot || it.lore) },
    h('input', { placeholder: 'Название (MiniMessage)', value: it.name ?? '', oninput: (e) => { it.name = e.target.value || undefined; commit(); } }),
    opts.slot !== false ? h('select', { onchange: (e) => { it.slot = e.target.value || undefined; commit(); } },
      ['', 'head', 'chest', 'legs', 'feet', 'offhand'].map((s) => h('option', { value: s }, s || 'слот: авто'))) : h('span'),
    h('input', { placeholder: 'Чары: sharpness=2, power=1', value: enchToText(it.enchants), oninput: (e) => { it.enchants = e.target.value.trim() ? textToEnch(e.target.value) : undefined; commit(); } }),
    h('label', { class: 'field check' }, h('input', { type: 'checkbox', checked: !!it.unbreakable, onchange: (e) => { it.unbreakable = e.target.checked || undefined; commit(); } }), 'Неразрушимый'));
  const slotSel = more.querySelector('select'); if (slotSel) slotSel.value = it.slot || '';
  return h('div', { class: 'item-ed' },
    h('input', { list: 'dl-materials', value: it.material, placeholder: 'MATERIAL', oninput: (e) => { it.material = e.target.value.toUpperCase(); commit(); } }),
    h('input', { type: 'number', min: 1, max: 99, value: it.amount ?? 1, title: 'Количество', oninput: (e) => { it.amount = parseInt(e.target.value, 10) || 1; commit(); } }),
    h('span', { class: 'muted' }, it.name ? mmPreview(it.name) : ''),
    h('button', { class: 'small', title: 'Название, чары, слот', onclick: () => { more.hidden = !more.hidden; } }, '⋯'),
    more);
}
/** Список предметов: obj[k] — массив. */
function itemsEditor(obj, k, title, opts) {
  const box = h('div', { class: 'field wide' });
  const draw = () => {
    if (!Array.isArray(obj[k])) obj[k] = [];
    const arr = obj[k];
    put(box, h('label', null, title),
      arr.length ? null : h('div', { class: 'empty' }, 'Пусто'),
      arr.map((_, i) => h('div', { class: 'row' }, itemEditor(arr, i, opts),
        h('button', { class: 'icon danger fixed', title: 'Удалить', onclick: () => { arr.splice(i, 1); markDirty(); draw(); } }, '✕'))),
      h('button', { class: 'small adder', onclick: () => { arr.push('STONE'); markDirty(); draw(); } }, '+ предмет'));
  };
  draw();
  return box;
}

// ------------------------------------------------------------------ словари и списки объектов
/**
 * Словарь id → объект (киты, тиры лута, модификаторы...).
 * spec: { title, make(), summary(entry,id), body(entry,id) -> Node }
 */
function dictEditor(holder, k, spec) {
  const box = h('div');
  const open = new Set();
  const draw = () => {
    if (!holder[k] || typeof holder[k] !== 'object' || Array.isArray(holder[k])) holder[k] = {};
    const dict = holder[k];
    const ids = Object.keys(dict);
    put(box, 
      ids.length ? null : h('div', { class: 'empty' }, spec.emptyText || 'Пока ничего нет'),
      ids.map((id) => {
        const entry = dict[id];
        const det = h('details', { class: 'entry', open: open.has(id),
          ontoggle: (e) => { if (e.target.open) open.add(id); else open.delete(id); } },
          h('summary', null,
            h('span', { class: 'title' }, h('code', null, id), spec.summary ? spec.summary(entry, id) : null),
            h('button', { class: 'small', title: 'Переименовать', onclick: (e) => { e.preventDefault(); rename(id); } }, '✎'),
            h('button', { class: 'small', title: 'Копировать', onclick: (e) => { e.preventDefault(); copy(id); } }, '⧉'),
            h('button', { class: 'small danger', title: 'Удалить', onclick: (e) => { e.preventDefault(); if (confirm(`Удалить «${id}»?`)) { delete dict[id]; markDirty(); draw(); } } }, '✕')),
          h('div', { class: 'body' }, spec.body(entry, id, draw)));
        return det;
      }),
      h('button', { class: 'small adder', onclick: () => {
        const id = (prompt('Id (латиница, цифры, _):') || '').trim();
        if (!id) return;
        if (!/^[A-Za-z0-9_-]+$/.test(id)) return toast('Неверный id', true);
        if (dict[id] !== undefined) return toast('Такой id уже есть', true);
        dict[id] = spec.make(); open.add(id); markDirty(); draw();
      } }, '+ ' + (spec.addText || 'добавить')));
    function rename(id) {
      const nid = (prompt('Новый id:', id) || '').trim();
      if (!nid || nid === id) return;
      if (dict[nid] !== undefined) return toast('Такой id уже есть', true);
      const rebuilt = {}; for (const key of Object.keys(dict)) rebuilt[key === id ? nid : key] = dict[key];
      holder[k] = rebuilt; open.delete(id); open.add(nid); markDirty(); draw();
    }
    function copy(id) {
      let n = 2; while (dict[`${id}_${n}`] !== undefined) n++;
      dict[`${id}_${n}`] = clone(dict[id]); markDirty(); draw();
    }
  };
  draw();
  return box;
}

/** Список объектов: holder[k] — массив. spec: { make(), body(item, i, arr) , summary(item) } */
function listEditor(holder, k, spec) {
  const box = h('div');
  const draw = () => {
    if (!Array.isArray(holder[k])) holder[k] = [];
    const arr = holder[k];
    put(box, 
      arr.length ? null : h('div', { class: 'empty' }, spec.emptyText || 'Пусто'),
      arr.map((item, i) => h('div', { class: 'list-row' },
        h('div', { class: 'tools' },
          spec.summary ? h('span', { style: 'flex:1' }, spec.summary(item, i)) : h('span', { style: 'flex:1' }),
          h('button', { class: 'small', disabled: i === 0, onclick: () => { [arr[i - 1], arr[i]] = [arr[i], arr[i - 1]]; markDirty(); draw(); } }, '↑'),
          h('button', { class: 'small', disabled: i === arr.length - 1, onclick: () => { [arr[i + 1], arr[i]] = [arr[i], arr[i + 1]]; markDirty(); draw(); } }, '↓'),
          h('button', { class: 'small danger', onclick: () => { arr.splice(i, 1); markDirty(); draw(); } }, '✕')),
        spec.body(item, i, arr))),
      h('button', { class: 'small adder', onclick: () => { arr.push(spec.make()); markDirty(); draw(); } }, '+ ' + (spec.addText || 'добавить')));
  };
  draw();
  return box;
}

/** Простой словарь ключ → значение (геймрулы). */
function kvEditor(holder, k, opts) {
  const box = h('div');
  const draw = () => {
    if (!holder[k] || typeof holder[k] !== 'object') holder[k] = {};
    const d = holder[k];
    put(box, 
      Object.keys(d).map((key) => h('div', { class: 'row', style: 'margin-bottom:6px' },
        h('input', { value: key, list: opts.list, onchange: (e) => {
          const nk = e.target.value.trim(); if (!nk || nk === key) return;
          const r = {}; for (const x of Object.keys(d)) r[x === key ? nk : x] = d[x]; holder[k] = r; markDirty(); draw(); } }),
        h('input', { value: d[key], oninput: (e) => { const v = e.target.value.trim(); d[key] = v === 'true' ? true : v === 'false' ? false : (/^-?\d+$/.test(v) ? parseInt(v, 10) : v); markDirty(); } }),
        h('button', { class: 'icon danger fixed', onclick: () => { delete d[key]; markDirty(); draw(); } }, '✕'))),
      h('button', { class: 'small adder', onclick: () => { const key = prompt(opts.prompt || 'Ключ:'); if (key) { d[key.trim()] = opts.def ?? ''; markDirty(); draw(); } } }, '+ ' + (opts.addText || 'добавить')));
  };
  draw();
  return box;
}

const card = (title, ...kids) => h('div', { class: 'card' }, title ? h('h3', null, title) : null, ...kids);

// ------------------------------------------------------------------ подсказки
const ACTIONS_HINT = 'Действия по одному на строку: message: …, broadcast: …, title: верх | низ, actionbar: …, sound: minecraft:…, ' +
  'time: 18000 lock, weather: clear|rain|thunder, gamerule: <id> <знач>, effect: speed 1 [сек], clear-effects, give: ARROW 16, heal, ' +
  'max-health: 40, border: <размер> [сек], refill-chests [tier], command: … {player}, mode: deathmatch|pvp-on|pvp-off';
const SIDEBAR_HINT = 'Плейсхолдеры: {map} {players} {max} {min} {need} {countdown} {state} {alive} {kills} {time} {elapsed} {next_event} {next_event_time} {kit}' +
  ' · Голодные игры: {pvp} · Билд баттл: {theme} {phase} {phase_time} {plot}';
const VOTE_LABELS = { MOST_VOTES: 'Побеждает сильнейший', RANDOM_VOTED: 'Случайный из проголосованных', WEIGHTED: 'Голоса добавляют шанс', NONE: 'Без голосования (случайно)' };
const voteSelect = (k, l) => ({ k, l, t: 'select', opts: () => S.meta.voteTypes, labels: VOTE_LABELS });

// ------------------------------------------------------------------ режимы
function modeTabs(d) {
  const type = d.type;
  return {
    'Основное': () => [
      card('Режим', fields(d, [
        { k: 'type', l: 'Тип логики', t: 'select', opts: () => S.meta.modeTypes, labels: { hunger_games: 'Голодные игры', build_battle: 'Билд баттл' } },
        { k: 'display-name', l: 'Название', t: 'mm' },
        { k: 'icon', l: 'Иконка в меню', t: 'material' },
        { k: 'description', l: 'Описание в меню', t: 'lines', preview: true },
      ])),
      card('Игроки и время', fields(d, [
        { k: 'min-players', l: 'Минимум игроков', t: 'int', hint: 'Отсчёт начинается, когда набралось' },
        { k: 'max-players', l: 'Максимум игроков', t: 'int', hint: 'Не больше, чем спавнов/участков на картах' },
        { k: 'max-sessions', l: 'Одновременных игр', t: 'int' },
        { k: 'countdown', l: 'Отсчёт (сек)', t: 'int' },
        { k: 'fast-countdown', l: 'Ускоренный старт (сек)', t: 'int', hint: 'Когда лобби заполнено' },
        { k: 'end-delay', l: 'Показ итогов (сек)', t: 'int' },
        { k: 'time-limit', l: 'Лимит времени игры', t: 'time', hint: '0 — без лимита' },
        { k: 'natural-mobs', l: 'Естественный спавн мобов', t: 'bool' },
      ])),
      card('Лобби ожидания', fields(d, [
        { k: 'waiting.template', l: 'Шаблон мира', list: 'dl-templates' },
        { k: 'waiting.spawn', l: 'Точка появления', ph: 'x y z yaw pitch', hint: 'Проще задать в игре: /mgp setup waiting ' },
        { k: 'waiting.void-y', l: 'Высота «пустоты»', t: 'num' },
      ])),
      card('Геймрулы', h('div', { class: 'hint muted', style: 'margin-bottom:8px' }, 'Id в новом формате (snake_case), полный список подсказывается при вводе.'),
        kvEditor(d, 'gamerules', { list: 'dl-gamerules', prompt: 'Id геймрула, например show_death_messages:', def: false, addText: 'геймрул' })),
    ],
    'Голосование и модификаторы': () => [
      card('Голосование за карту', fields(d, [
        voteSelect('map-vote.type', 'Тип голосования'),
        { k: 'map-vote.options', l: 'Сколько карт показывать', t: 'int', hint: '0 — все карты режима' },
      ])),
      card('Случайные модификаторы',
        h('p', { class: 'muted' }, 'Группа — это одна «ось» (время суток, погода...). Способ выбора: VOTE — голосуют в лобби, RANDOM — случайно по весу, FIXED — всегда один вариант, OFF — выключено.'),
        dictEditor(d, 'modifiers', {
          addText: 'группа модификаторов',
          make: () => ({ display: 'Новая группа', icon: 'CLOCK', selection: 'RANDOM', 'vote-type': 'MOST_VOTES', options: {} }),
          summary: (g) => [mmPreview(g.display), h('span', { class: 'badge info' }, g.selection || 'RANDOM')],
          body: (g) => h('div', null,
            fields(g, [
              { k: 'display', l: 'Название', t: 'mm' },
              { k: 'icon', l: 'Иконка', t: 'material' },
              { k: 'selection', l: 'Способ выбора', t: 'select', opts: ['VOTE', 'RANDOM', 'FIXED', 'OFF'],
                labels: { VOTE: 'Голосование', RANDOM: 'Случайно', FIXED: 'Всегда один', OFF: 'Выключено' } },
              voteSelect('vote-type', 'Тип голосования'),
              { k: 'fixed', l: 'Вариант для FIXED', t: 'text', optional: true },
            ]),
            h('h4', null, 'Варианты'),
            dictEditor(g, 'options', {
              addText: 'вариант',
              make: () => ({ display: 'Вариант', icon: 'PAPER', weight: 1, actions: [] }),
              summary: (o) => [mmPreview(o.display), h('span', { class: 'badge' }, 'вес ' + (o.weight ?? 1))],
              body: (o) => fields(o, [
                { k: 'display', l: 'Название', t: 'mm' },
                { k: 'icon', l: 'Иконка', t: 'material' },
                { k: 'weight', l: 'Вес (шанс)', t: 'num' },
                { k: 'actions', l: 'Действия', t: 'lines', hint: ACTIONS_HINT },
              ]),
            })),
        })),
    ],
    'Наборы': () => [
      card('Наборы (киты)', fields(d, [{ k: 'default-kit', l: 'Набор по умолчанию', t: 'select', empty: '— нет —', opts: () => Object.keys(d.kits || {}) }]),
        h('br'),
        dictEditor(d, 'kits', {
          addText: 'набор',
          make: () => ({ display: 'Новый набор', icon: 'CHEST', description: [], items: [] }),
          summary: (k) => [mmPreview(k.display), k.permission ? h('span', { class: 'badge' }, k.permission) : null],
          body: (k) => h('div', null,
            fields(k, [
              { k: 'display', l: 'Название', t: 'mm' },
              { k: 'icon', l: 'Иконка', t: 'material' },
              { k: 'permission', l: 'Право (пусто — всем)', optional: true, ph: 'mgp.kit.vip' },
              { k: 'description', l: 'Описание', t: 'lines', preview: true },
            ]),
            h('div', { class: 'grid' }, itemsEditor(k, 'items', 'Предметы')),
            fields(k, [{ k: 'actions', l: 'Действия при выдаче (например эффекты)', t: 'lines', hint: ACTIONS_HINT }])),
        })),
      card('Стартовые предметы для всех', h('div', { class: 'grid' }, itemsEditor(d, 'start-items', 'Предметы'))),
    ],
    'Сундуки': () => [
      card('Тиры сундуков',
        h('p', { class: 'muted' }, 'Сундук на карте ссылается на тир по id (в игре: /mgp setup chest <карта> <тир>). Вес — относительная частота предмета.'),
        dictEditor(d, 'loot', {
          addText: 'тир',
          make: () => ({ rolls: '3-6', items: [] }),
          summary: (t) => h('span', { class: 'muted' }, `${(t.items || []).length} предм., по ${t.rolls ?? '3-6'} в сундуке`),
          body: (t) => h('div', null,
            fields(t, [{ k: 'rolls', l: 'Предметов в сундуке', ph: '3-6' }]),
            h('h4', null, 'Предметы'),
            listEditor(t, 'items', {
              addText: 'предмет', make: () => ({ item: 'STONE', weight: 1 }),
              body: (e) => h('div', { class: 'grid' },
                h('div', { class: 'field wide' }, h('label', null, 'Предмет'), itemEditor(e, 'item', { slot: false })),
                renderField(e, { k: 'weight', l: 'Вес', t: 'int' }),
                renderField(e, { k: 'amount', l: 'Количество (диапазон)', ph: '1-3', optional: true })),
            })),
        })),
    ],
    'Торговцы': () => [
      card('Наборы сделок',
        h('p', { class: 'muted' }, 'Торговец на карте ссылается на набор по id (в игре: /mgp setup trader <карта> <набор> [профессия]).'),
        dictEditor(d, 'trades', {
          addText: 'набор сделок',
          make: () => [],
          summary: (list) => h('span', { class: 'muted' }, `${(list || []).length} сделок`),
          body: (_, id) => listEditor(d.trades, id, {
            addText: 'сделку', make: () => ({ result: 'ARROW 8', cost: 'GOLD_INGOT 2', 'max-uses': 999 }),
            body: (t) => h('div', { class: 'grid' },
              h('div', { class: 'field wide' }, h('label', null, 'Товар'), itemEditor(t, 'result', { slot: false })),
              h('div', { class: 'field wide' }, h('label', null, 'Цена'), itemEditor(t, 'cost', { slot: false })),
              t.cost2 !== undefined
                ? h('div', { class: 'field wide' }, h('label', null, 'Цена 2'), h('div', { class: 'row' }, itemEditor(t, 'cost2', { slot: false }),
                    h('button', { class: 'icon danger fixed', onclick: (e) => { delete t.cost2; markDirty(); e.target.closest('.field').remove(); } }, '✕')))
                : h('div', { class: 'field' }, h('button', { class: 'small', onclick: () => { t.cost2 = 'EMERALD'; markDirty(); render(); } }, '+ вторая цена')),
              renderField(t, { k: 'max-uses', l: 'Лимит покупок', t: 'int' })),
          }),
        })),
    ],
    'События': () => [
      card('События по времени',
        h('p', { class: 'muted' }, 'Срабатывают через указанное время после начала игры. Название показывается в инфо-панели как «следующее событие».'),
        listEditor(d, 'timeline', {
          addText: 'событие', make: () => ({ name: '<aqua>Событие', at: '5:00', actions: ['message: <aqua>Событие!'] }),
          summary: (e) => [h('b', null, typeof e.at === 'number' ? secToTime(e.at) : (e.at ?? '?')), ' ', mmPreview(e.name)],
          body: (e) => fields(e, [
            { k: 'name', l: 'Название (для инфо-панели)', t: 'mm' },
            { k: 'at', l: 'Когда', t: 'time' },
            { k: 'actions', l: 'Действия', t: 'lines', hint: ACTIONS_HINT },
          ]),
        })),
      card('Награда победителю', fields(d, [{ k: 'win-actions', l: 'Действия для победителя', t: 'lines', hint: ACTIONS_HINT }])),
    ],
    'Инфо-панель': () => {
      if (!d.sidebar || typeof d.sidebar !== 'object') d.sidebar = {};
      const groups = type === 'build_battle'
        ? [['waiting', 'Лобби ожидания'], ['build', 'Строительство'], ['judging', 'Оценка'], ['playing', 'Игра (запасной)'], ['ending', 'Итоги']]
        : [['waiting', 'Лобби ожидания'], ['playing', 'Игра'], ['ending', 'Итоги']];
      return [
        card('Инфо-панель справа', h('p', { class: 'muted' }, SIDEBAR_HINT),
          fields(d.sidebar, [{ k: 'title', l: 'Заголовок', t: 'mm' }])),
        h('div', { class: 'grid' }, groups.map(([g, l]) =>
          card(l, fields(d.sidebar, [{ k: g, l: 'Строки (до 15)', t: 'lines', keepEmpty: true, preview: true, rows: 9 }])))),
      ];
    },
    'Настройки режима': () => {
      if (!d.settings || typeof d.settings !== 'object') d.settings = {};
      const s = d.settings;
      if (type === 'hunger_games') return [card('Голодные игры', fields(s, [
        { k: 'freeze', l: 'Заморозка на спавнах (сек)', t: 'int' },
        { k: 'grace-period', l: 'Без PvP после старта (сек)', t: 'int' },
        { k: 'allow-place', l: 'Можно ставить блоки', t: 'bool' },
        { k: 'disable-hunger', l: 'Отключить голод', t: 'bool' },
        { k: 'time-up', l: 'Когда вышло время', t: 'select', opts: ['draw', 'most-kills'], labels: { draw: 'Ничья', 'most-kills': 'Побеждает лучший по убийствам' } },
        { k: 'breakable-blocks', l: 'Какие блоки можно ломать', t: 'lines', ph: 'OAK_LEAVES' },
      ]))];
      if (type === 'build_battle') return [
        card('Билд баттл', fields(s, [
          { k: 'build-time', l: 'Время на стройку', t: 'time' },
          { k: 'judge-time', l: 'Секунд на оценку одной постройки', t: 'int' },
          { k: 'plot-margin', l: 'Запас за границей участка', t: 'int' },
          { k: 'disable-hunger', l: 'Отключить голод', t: 'bool' },
          { k: 'theme-options', l: 'Тем на голосовании', t: 'int' },
          voteSelect('theme-vote', 'Голосование за тему'),
          { k: 'themes', l: 'Темы', t: 'lines' },
          { k: 'banned-items', l: 'Запрещённые предметы', t: 'lines' },
        ])),
        card('Оценки', listEditor(s, 'grades', {
          addText: 'оценку', make: () => ({ name: 'Оценка', material: 'WHITE_WOOL', points: 1 }),
          summary: (g) => [mmPreview(g.name), ' ', h('span', { class: 'badge' }, (g.points ?? 0) + ' очк.')],
          body: (g) => fields(g, [{ k: 'name', l: 'Название', t: 'mm' }, { k: 'material', l: 'Предмет', t: 'material' }, { k: 'points', l: 'Очки', t: 'int' }]),
        })),
      ];
      return [card('Настройки', h('p', { class: 'muted' }, 'У этого типа режима нет формы — используй вкладку YAML.'))];
    },
  };
}
function secToTime(s) { return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

// ------------------------------------------------------------------ карты
function mapTabs(d) {
  return {
    'Основное': () => [
      card('Карта', fields(d, [
        { k: 'mode', l: 'Режим', t: 'select', opts: () => S.meta.modes },
        { k: 'display-name', l: 'Название', t: 'mm' },
        { k: 'icon', l: 'Иконка', t: 'material' },
        { k: 'template', l: 'Шаблон мира', list: 'dl-templates', hint: 'Папка в plugins/MGP/templates (в игре: /mgp setup export <имя>)' },
        { k: 'weight', l: 'Вес при случайном выборе', t: 'num' },
        { k: 'description', l: 'Описание (в голосовании)', t: 'lines', preview: true },
      ])),
      sourceCard(d),
      card('Барьер и наблюдатели', fields(d, [
        { k: 'center', l: 'Центр карты', ph: 'x y z' },
        { k: 'border-size', l: 'Размер барьера', t: 'num', hint: '0 — без барьера' },
        { k: 'spectator', l: 'Точка наблюдателей', ph: 'x y z yaw pitch' },
      ])),
      card('Мир карты', fields(d, [
        { k: 'biome', l: 'Биом на всю карту', list: 'dl-biomes', ph: 'как в шаблоне', hint: 'Например plains, snowy_plains, desert. Пусто — биомы из шаблона. В игре: /mgp setup biome <карта> <биом>' },
      ]), h('div', { class: 'hint muted', style: 'margin:10px 0 8px' }, 'Геймрулы только для этой карты (важнее геймрулов режима). По умолчанию во всех мирах игр random_tick_speed 0 — config.yml → worlds.default-gamerules.'),
        kvEditor(d, 'gamerules', { list: 'dl-gamerules', prompt: 'Id геймрула, например random_tick_speed:', def: false, addText: 'геймрул' })),
    ],
    'Спавны и сундуки': () => [
      card('Спавны игроков', fields(d, [{ k: 'spawns', l: 'x y z yaw pitch — по одному на строку', t: 'lines', rows: 10,
        hint: `Сейчас: ${(d.spawns || []).length}. Кривой спавн: сотри его строку и сохрани. В игре: /mgp setup spawn <карта> добавить, /mgp setup delspawn <карта> удалить ближайший, /mgp setup spawns <карта> список` }])),
      card('Сундуки', fields(d, [{ k: 'chests', l: 'x y z тир — по одному на строку', t: 'lines', rows: 10,
        hint: 'В игре: смотри на сундук и пиши /mgp setup chest <карта> <тир>' }])),
    ],
    'Торговцы': () => [card('Торговцы', listEditor(d, 'traders', {
      addText: 'торговца', make: () => ({ pos: '0.5 64 0.5', set: 'default', name: '<yellow>Торговец', profession: 'librarian' }),
      summary: (t) => [mmPreview(t.name), ' ', h('span', { class: 'badge' }, t.set)],
      body: (t) => fields(t, [
        { k: 'pos', l: 'Позиция', ph: 'x y z yaw pitch' },
        { k: 'set', l: 'Набор сделок (id из режима)' },
        { k: 'name', l: 'Имя', t: 'mm' },
        { k: 'profession', l: 'Профессия', list: 'dl-professions' },
      ]),
    }))],
    'Триггеры': () => [card('Триггеры',
      h('p', { class: 'muted' }, 'Область между min и max. ONCE — один раз за игру, PER_PLAYER — один раз для каждого игрока, REPEAT — каждую секунду, пока игрок внутри.'),
      listEditor(d, 'triggers', {
        addText: 'триггер', make: () => ({ name: 'trigger', min: '0 0 0', max: '0 0 0', mode: 'ONCE', actions: [] }),
        summary: (t) => [h('b', null, t.name), ' ', h('span', { class: 'badge info' }, t.mode || 'ONCE')],
        body: (t) => fields(t, [
          { k: 'name', l: 'Имя' },
          { k: 'mode', l: 'Срабатывание', t: 'select', opts: ['ONCE', 'PER_PLAYER', 'REPEAT'] },
          { k: 'min', l: 'Угол 1', ph: 'x y z' },
          { k: 'max', l: 'Угол 2', ph: 'x y z' },
          { k: 'actions', l: 'Действия', t: 'lines', hint: ACTIONS_HINT },
        ]),
      }))],
    'Участки': () => [card('Участки (Билд баттл)',
      h('p', { class: 'muted' }, 'В игре: /mgp setup pos1, /mgp setup pos2, затем /mgp setup plot <карта> в точке появления.'),
      listEditor(d, 'plots', {
        addText: 'участок', make: () => ({ min: '0 60 0', max: '20 90 20', spawn: '10.5 61 10.5' }),
        summary: (_, i) => h('b', null, '#' + (i + 1)),
        body: (p) => fields(p, [{ k: 'min', l: 'Угол 1', ph: 'x y z' }, { k: 'max', l: 'Угол 2', ph: 'x y z' }, { k: 'spawn', l: 'Точка появления', ph: 'x y z yaw pitch' }]),
      }))],
  };
}

/** Откуда карта была вырезана + кнопка пересоздать шаблон. */
function sourceCard(d) {
  const src = d.source;
  if (!src || !src.world) return card('Источник', h('p', { class: 'muted' }, 'Карта не импортировалась из мира. Импорт: пункт «Импорт карты из мира» слева или /mgp import.'));
  const id = location.hash.split('/')[2];
  const btn = h('button', { onclick: async () => {
    if (S.dirty && !confirm('Несохранённые изменения формы будут потеряны. Продолжить?')) return;
    const chests = confirm('Найти сундуки заново? «ОК» — список сундуков будет перезаписан, «Отмена» — оставить как есть.');
    const [x1, y1, z1] = String(src.min).split(/\s+/), [x2, y2, z2] = String(src.max).split(/\s+/);
    btn.disabled = true; btn.textContent = 'Вырезаю...';
    try {
      const r = await api('POST', 'import', { map: id, mode: d.mode, world: src.world, x1, y1, z1, x2, y2, z2, detectChests: chests, optimize: optimize.checked });
      S.dirty = false; toast('Шаблон пересоздан: ' + r.summary); route();
    } catch (e) { toast(e.message, true); btn.disabled = false; btn.textContent = 'Пересоздать шаблон из мира'; }
  } }, 'Пересоздать шаблон из мира');
  const optimize = h('input', { type: 'checkbox', checked: src.optimized !== false });
  return card('Источник', h('p', null, `Мир `, h('b', null, src.world), `, область `, h('code', null, src.min), ' — ', h('code', null, src.max)),
    h('p', { class: 'muted' }, 'Если поменял карту в исходном мире, пересоздай шаблон. Спавны и настройки сохранятся.'),
    h('label', { class: 'field check', style: 'margin-bottom:10px' }, optimize, 'Оптимизация границ: обрезать края, где только воздух'), btn);
}

function pageImport(main) {
  const f = { map: '', mode: S.meta.modes[0] || '', world: S.meta.worlds[0] || '', name: '', x1: '', y1: '', z1: '', x2: '', y2: '', z2: '', detectChests: true, optimize: true };
  const inp = (k, ph, attrs) => h('input', { value: f[k], placeholder: ph, ...attrs, oninput: (e) => { f[k] = e.target.value; } });
  const sel = (k, opts) => { const el = h('select', { onchange: (e) => { f[k] = e.target.value; } }, opts.map((o) => h('option', { value: o }, o))); el.value = f[k]; return el; };
  const out = h('div');
  const go = h('button', { class: 'primary', onclick: async () => {
    go.disabled = true; go.textContent = 'Вырезаю карту...';
    put(out, card(null, h('p', { class: 'muted' }, 'Сервер копирует нужные чанки в отдельный шаблон. Для большой карты это может занять до минуты.')));
    try {
      const r = await api('POST', 'import', { ...f, map: f.map.trim().toLowerCase() });
      put(out, card('Готово', h('p', null, r.summary),
        h('p', { class: 'muted' }, 'Координаты в шаблоне те же, что в исходном мире. Спавны удобно ставить прямо там: встань на точку и введи команду ', h('code', null, '/mgp setup spawn ' + r.map)),
        h('button', { onclick: () => { location.hash = '#/map/' + r.map; } }, 'Открыть карту →')));
      await refreshLists();
    } catch (e) { put(out, card('Ошибка', h('p', { style: 'color:var(--bad)' }, e.message))); }
    go.disabled = false; go.textContent = 'Импортировать';
  } }, 'Импортировать');
  const fld = (l, el, hint) => h('div', { class: 'field' }, h('label', null, l), el, hint ? h('div', { class: 'hint' }, hint) : null);
  put(main, h('div', { class: 'page' },
    h('div', { class: 'page-head' }, h('h1', null, 'Импорт карты из мира')),
    card('Что это', h('p', { class: 'muted' }, 'Укажи два противоположных угла готовой карты в большом мире. Плагин вырежет только эти чанки в отдельный шаблон ' +
      '(игры не будут копировать весь мир), создаст файл карты с центром и барьером и найдёт сундуки (обычный сундук → tier1, ловушка → tier2; меняется в config.yml → import.chest-tiers). ' +
      'Если карта с таким id уже есть, шаблон пересоздаётся, а спавны и настройки остаются.')),
    card('Карта', h('div', { class: 'grid' },
      fld('Id карты', inp('map', 'например hns_castle'), 'a-z, 0-9 и _'),
      fld('Режим', sel('mode', S.meta.modes)),
      fld('Название', inp('name', '<green>Замок'), 'MiniMessage, можно оставить пустым'),
      fld('Мир', sel('world', S.meta.worlds)))),
    card('Область', h('p', { class: 'muted' }, 'Координаты можно посмотреть на F3 или в варпах Essentials. Высота нужна для поиска сундуков и точки наблюдателей.'),
      h('div', { class: 'grid' },
        fld('Угол 1: X', inp('x1', 'x', { type: 'number' })), fld('Угол 1: Y', inp('y1', 'y', { type: 'number' })), fld('Угол 1: Z', inp('z1', 'z', { type: 'number' })),
        fld('Угол 2: X', inp('x2', 'x', { type: 'number' })), fld('Угол 2: Y', inp('y2', 'y', { type: 'number' })), fld('Угол 2: Z', inp('z2', 'z', { type: 'number' }))),
      h('label', { class: 'field check', style: 'margin-top:12px' },
        h('input', { type: 'checkbox', checked: true, onchange: (e) => { f.detectChests = e.target.checked; } }), 'Найти сундуки в области'),
      h('label', { class: 'field check', style: 'margin-top:6px' },
        h('input', { type: 'checkbox', checked: true, onchange: (e) => { f.optimize = e.target.checked; } }),
        'Оптимизация границ: обрезать края области, где только воздух (можно выделять с запасом)'),
      h('div', { style: 'margin-top:12px' }, go)),
    out));
}

// ------------------------------------------------------------------ общий конфиг
function configTabs(d) {
  return {
    'Хаб': () => [
      card('Главное лобби', fields(d, [
        { k: 'prefix', l: 'Префикс сообщений', t: 'mm' },
        { k: 'hub.world', l: 'Мир хаба' },
        { k: 'hub.spawn', l: 'Точка появления', ph: 'x y z yaw pitch' },
        { k: 'hub.gamemode', l: 'Режим игры', t: 'select', opts: ['ADVENTURE', 'SURVIVAL', 'CREATIVE'] },
        { k: 'hub.protect', l: 'Защита хаба (без урона, голода, ломания)', t: 'bool' },
      ])),
      card('Инфо-панель в хабе', h('p', { class: 'muted' }, 'Плейсхолдеры: {player} {online} {players_<режим>}'),
        fields(d, [{ k: 'hub.sidebar.title', l: 'Заголовок', t: 'mm' }, { k: 'hub.sidebar.lines', l: 'Строки', t: 'lines', keepEmpty: true, preview: true, rows: 10 }])),
    ],
    'Голосование': () => [card('Тип WEIGHTED (голоса добавляют шанс)',
      h('p', { class: 'muted' }, 'Вес варианта = weight × base + голоса × per-vote'),
      fields(d, [{ k: 'voting.weighted.base', l: 'base', t: 'num' }, { k: 'voting.weighted.per-vote', l: 'per-vote', t: 'num' }]))],
    'Админ-панель': () => [card('Админ-панель', fields(d, [
      { k: 'panel.enabled', l: 'Включена', t: 'bool' },
      { k: 'panel.host', l: 'Адрес', hint: '127.0.0.1 — только с этого компьютера; 0.0.0.0 — по сети' },
      { k: 'panel.port', l: 'Порт', t: 'int' },
      { k: 'panel.public-url', l: 'Внешний адрес', ph: 'http://1.2.3.4:8765' },
    ]), h('p', { class: 'muted' }, 'Изменения адреса и порта применяются после перезапуска сервера. Ключ доступа хранится в config.yml (panel.token).'))],
  };
}

// ------------------------------------------------------------------ страницы
async function refreshLists() {
  [S.modes, S.maps] = await Promise.all([api('GET', 'modes'), api('GET', 'maps')]);
  drawNav();
}

function drawNav() {
  const route = location.hash.slice(2);
  const link = (href, label, extra) => h('a', { class: 'item' + (route === href ? ' active' : ''), href: '#/' + href }, h('span', null, label), extra);
  const nav = $('#nav');
  if (!nav) return;
  const byMode = {};
  for (const m of S.maps) (byMode[m.mode] = byMode[m.mode] || []).push(m);
  put(nav, 
    h('div', { class: 'brand' }, h('img', { src: 'icon.svg', alt: '' }), 'MGP Admin'),
    link('overview', 'Обзор'),
    link('config', 'Общие настройки'),
    link('import', 'Импорт карты из мира'),
    h('h4', null, 'Режимы', h('button', { title: 'Новый режим', onclick: newMode }, '+')),
    S.modes.map((m) => link('mode/' + m.id, mmText(m.name) || m.id, m.valid ? h('small', null, m.id) : h('small', { class: 'warn-dot', title: 'Ошибка в файле' }, '⚠'))),
    h('h4', null, 'Карты', h('button', { title: 'Новая карта', onclick: newMap }, '+')),
    Object.keys(byMode).sort().map((mode) => [
      h('div', { class: 'muted', style: 'padding:4px 16px 0;font-size:12px' }, mode),
      byMode[mode].map((m) => link('map/' + m.id, mmText(m.name) || m.id,
        !m.valid ? h('small', { class: 'warn-dot', title: 'Ошибка в файле' }, '⚠') : !m.template ? h('small', { class: 'warn-dot', title: 'Нет шаблона мира' }, 'нет мира') : null))]),
    h('div', { class: 'bottom' },
      h('button', { onclick: popout }, '⧉ Открыть в отдельном окне'),
      h('button', { onclick: async () => { try { await api('POST', 'reload'); toast('Конфиги перезагружены'); await refreshLists(); } catch (e) { toast(e.message, true); } } }, '↻ Перезагрузить конфиги'),
      h('button', { onclick: () => { store('mgp_token', null); location.reload(); } }, 'Выйти')));
}

function popout() {
  const w = window.open(location.origin + '/#/overview', 'mgp_admin', 'popup=yes,width=1280,height=820');
  if (!w) toast('Браузер заблокировал окно. Разреши всплывающие окна для этой страницы.', true);
}

async function newMode() {
  if (!confirmLeave()) return;
  const id = (prompt('Id нового режима (a-z, 0-9, _):') || '').trim().toLowerCase();
  if (!id) return;
  const type = prompt('Тип логики (' + S.meta.modeTypes.join(', ') + '):', S.meta.modeTypes[0]);
  if (!type) return;
  const copyFrom = prompt('Скопировать настройки из режима (пусто — с нуля):', S.modes.find((m) => m.type === type)?.id || '') || '';
  try { await api('POST', 'modes', { id, type, copyFrom }); await refreshLists(); location.hash = '#/mode/' + id; toast('Режим создан'); }
  catch (e) { toast(e.message, true); }
}
async function newMap() {
  if (!confirmLeave()) return;
  const id = (prompt('Id новой карты (a-z, 0-9, _):') || '').trim().toLowerCase();
  if (!id) return;
  const mode = prompt('Режим (' + S.meta.modes.join(', ') + '):', S.meta.modes[0] || '');
  if (!mode) return;
  try { await api('POST', 'maps', { id, mode }); await refreshLists(); location.hash = '#/map/' + id; toast('Карта создана'); }
  catch (e) { toast(e.message, true); }
}

function confirmLeave() { return !S.dirty || confirm('Есть несохранённые изменения. Уйти без сохранения?'); }

async function pageOverview(main) {
  const draw = async () => {
    let o;
    try { o = await api('GET', 'overview'); } catch (e) { return; }
    if (location.hash.slice(2) !== 'overview') return;
    const STATES = { CREATING: 'Подготовка', WAITING: 'Ожидание', STARTING: 'Отсчёт', PLAYING: 'Игра', ENDING: 'Итоги', CLOSED: 'Закрыта' };
    put(main, h('div', { class: 'page' },
      h('div', { class: 'page-head' }, h('h1', null, 'Обзор'), h('span', { class: 'status' }, o.server)),
      h('div', { class: 'stats' },
        h('div', { class: 'stat' }, h('b', null, o.online.length), h('span', null, 'игроков онлайн')),
        h('div', { class: 'stat' }, h('b', null, o.sessions.length), h('span', null, 'активных игр')),
        h('div', { class: 'stat' }, h('b', { style: `color:${o.tps >= 18 ? 'var(--ok)' : 'var(--bad)'}` }, o.tps), h('span', null, 'TPS'))),
      card('Игры', o.sessions.length ? h('table', null,
        h('tr', null, ['#', 'Режим', 'Состояние', 'Карта', 'Игроки', 'Время', ''].map((t) => h('th', null, t))),
        o.sessions.map((s) => h('tr', null,
          h('td', null, s.id), h('td', null, s.mode), h('td', null, h('span', { class: 'badge ' + (s.state === 'PLAYING' ? 'ok' : 'info') }, STATES[s.state] || s.state)),
          h('td', null, s.map || '—'),
          h('td', null, `${s.players.length}/${s.max}`, s.state === 'PLAYING' ? h('span', { class: 'muted' }, ` (живых ${s.alive})`) : null, h('div', { class: 'muted' }, s.players.join(', '))),
          h('td', null, s.state === 'PLAYING' ? secToTime(s.elapsed) : '—'),
          h('td', null,
            ['WAITING', 'STARTING'].includes(s.state) ? h('button', { class: 'small', onclick: () => act(s.id, 'start') }, 'Старт') : null, ' ',
            h('button', { class: 'small danger', onclick: () => { if (confirm('Завершить игру #' + s.id + '? Игроки вернутся в хаб.')) act(s.id, 'close'); } }, 'Закрыть'))))) :
        h('div', { class: 'empty' }, 'Сейчас никто не играет')),
      h('div', { class: 'grid' },
        card('Режимы', h('table', null, o.modes.map((m) => h('tr', null, h('td', null, h('a', { href: '#/mode/' + m.id }, mmPreview(m.name))),
          h('td', { class: 'muted' }, `карт: ${m.maps}`), h('td', null, `играют: ${m.players}`))))),
        card('Онлайн', o.online.length ? o.online.map((p) => h('div', null, p.name, p.session ? h('span', { class: 'muted' }, ` — игра #${p.session}`) : h('span', { class: 'muted' }, ' — хаб'))) : h('div', { class: 'empty' }, 'Никого')))));
  };
  const act = async (id, a) => { try { await api('POST', `sessions/${id}/${a}`); toast('Готово'); draw(); } catch (e) { toast(e.message, true); } };
  await draw();
  S.timer = setInterval(draw, 3000);
}

/** Общая страница редактора: вкладки-формы + вкладка YAML. */
async function pageEditor(main, kind, id) {
  const path = kind === 'config' ? 'config' : (kind === 'mode' ? 'modes/' : 'maps/') + id;
  let data;
  try { data = await api('GET', path); } catch (e) { put(main, h('div', { class: 'page' }, card('Ошибка', e.message))); return; }
  const tabs = kind === 'mode' ? modeTabs(data) : kind === 'map' ? mapTabs(data) : configTabs(data);
  tabs['YAML'] = null;
  const names = Object.keys(tabs);
  let current = load('mgp_tab_' + kind);
  if (!names.includes(current)) current = names[0];
  const title = kind === 'config' ? 'Общие настройки' : (kind === 'mode' ? 'Режим: ' : 'Карта: ') + id;
  const body = h('div');
  const saveBtn = h('button', { id: 'save-btn', class: 'primary', disabled: true, onclick: save }, 'Сохранить');
  const head = h('div', { class: 'page-head' }, h('h1', null, title), h('span', { id: 'save-status', class: 'status' }, ''),
    kind === 'map' ? h('button', { onclick: rename }, 'Переименовать') : null,
    kind !== 'config' ? h('button', { class: 'danger', onclick: del }, 'Удалить') : null, saveBtn);
  const tabBar = h('div', { class: 'tabs' });
  put(main, h('div', { class: 'page' }, head, tabBar, body));

  window.render = drawTab; // для мест, которым нужна полная перерисовка
  drawTab();

  function drawTab() {
    put(tabBar, ...names.map((n) => h('button', { class: n === current ? 'active' : '', onclick: () => {
      if (current === 'YAML' && S.dirty && !confirm('Изменения в YAML не сохранены. Переключиться?')) return;
      if (current === 'YAML' && S.dirty) { S.dirty = false; }
      current = n; store('mgp_tab_' + kind, n); drawTab(); } }, n)));
    saveBtn.hidden = current === 'YAML';
    if (current === 'YAML') return drawYaml();
    put(body, ...[].concat(tabs[current]()));
  }

  async function drawYaml() {
    if (S.dirty && !confirm('Несохранённые изменения в формах будут потеряны. Продолжить?')) { current = names[0]; return drawTab(); }
    S.dirty = false;
    const rawPath = 'raw/' + kind + '/' + (id || 'config');
    let text = '';
    try { text = (await api('GET', rawPath)).text; } catch (e) { toast(e.message, true); }
    const ta = h('textarea', { class: 'yaml', spellcheck: 'false', oninput: () => { S.dirty = true; } }, text);
    put(body, card(null,
      h('p', { class: 'muted' }, 'Полный файл как есть, с комментариями. Перед сохранением плагин проверит, что настройки разбираются без ошибок.'),
      ta, h('div', { class: 'row', style: 'margin-top:10px;justify-content:flex-end' },
        h('button', { class: 'primary fixed', onclick: async () => {
          try { await api('PUT', rawPath, { text: ta.value }); S.dirty = false; toast('YAML сохранён и применён'); data = await api('GET', path); Object.assign(tabs, kind === 'mode' ? modeTabs(data) : kind === 'map' ? mapTabs(data) : configTabs(data), { YAML: null }); await refreshLists(); }
          catch (e) { toast(e.message, true); }
        } }, 'Сохранить YAML'))));
  }

  async function save() {
    saveBtn.disabled = true;
    try {
      await api('PUT', path, data);
      clearDirty();
      toast('Сохранено и применено. Новые игры уже используют эти настройки.');
      await refreshLists();
    } catch (e) { saveBtn.disabled = false; toast(e.message, true); }
  }
  async function rename() {
    if (S.dirty && !confirm('Несохранённые изменения будут потеряны. Продолжить?')) return;
    const to = (prompt(`Новый id карты «${id}» (a-z, 0-9 и _). Спавны, сундуки и шаблон перенесутся.`, id) || '').trim().toLowerCase();
    if (!to || to === id) return;
    const name = prompt('Новое название (MiniMessage). Оставь пустым, чтобы не менять.', '') || '';
    try {
      const r = await api('POST', `maps/${id}/rename`, { to, name: name.trim() || null });
      S.dirty = false; toast(r.message); await refreshLists(); location.hash = '#/map/' + r.map;
    } catch (e) { toast(e.message, true); }
  }
  async function del() {
    if (!confirm(`Удалить ${kind === 'mode' ? 'режим' : 'карту'} «${id}»? Файл будет удалён.`)) return;
    try { await api('DELETE', path); S.dirty = false; toast('Удалено'); await refreshLists(); location.hash = '#/overview'; } catch (e) { toast(e.message, true); }
  }
}

// ------------------------------------------------------------------ вход и маршрутизация
function showLogin(err) {
  clearInterval(S.timer);
  put($('#app'), h('div', { class: 'login' }, h('div', { class: 'card' },
    h('div', { class: 'brand', style: 'padding:0 0 12px' }, h('img', { src: 'icon.svg', alt: '' }), 'MGP Admin'),
    h('p', { class: 'muted' }, 'Введи ключ доступа. Ссылку с ключом можно получить в игре командой /mgp panel (нужно право mgp.admin) или в config.yml → panel.token.'),
    err ? h('p', { style: 'color:var(--bad)' }, err) : null,
    h('form', { onsubmit: async (e) => {
      e.preventDefault();
      S.token = e.target.token.value.trim();
      store('mgp_token', S.token);
      try { await api('GET', 'ping'); boot(); } catch (_) {}
    } }, h('input', { name: 'token', placeholder: 'Ключ доступа', autocomplete: 'off', autofocus: true }),
      h('button', { class: 'primary', style: 'margin-top:10px;width:100%' }, 'Войти')))));
}

async function boot() {
  try {
    S.meta = await api('GET', 'meta');
  } catch (e) { if (!$('.login')) showLogin(e.message); return; }
  const fill = (id, arr) => put($(id), ...arr.map((v) => h('option', { value: v })));
  fill('#dl-materials', S.meta.materials); fill('#dl-templates', S.meta.templates); fill('#dl-effects', S.meta.effects);
  fill('#dl-professions', S.meta.professions); fill('#dl-gamerules', S.meta.gamerules); fill('#dl-biomes', S.meta.biomes || []); fill('#dl-modes', S.meta.modes);
  put($('#app'), h('div', { class: 'layout' }, h('aside', { id: 'nav', class: 'nav' }), h('main', { id: 'main' })));
  await refreshLists();
  if (!location.hash.startsWith('#/')) location.hash = '#/overview';
  else route();
}

let lastRoute = null;
async function route() {
  const r = location.hash.slice(2);
  if (lastRoute !== null && r !== lastRoute && !confirmLeave()) { history.replaceState(null, '', '#/' + lastRoute); return; }
  lastRoute = r;
  S.dirty = false;
  clearInterval(S.timer);
  drawNav();
  const main = $('#main');
  if (!main) return;
  main.scrollTop = 0;
  const [page, id] = r.split('/');
  if (page === 'mode') await pageEditor(main, 'mode', id);
  else if (page === 'map') await pageEditor(main, 'map', id);
  else if (page === 'config') await pageEditor(main, 'config', null);
  else if (page === 'import') pageImport(main);
  else await pageOverview(main);
}

window.addEventListener('hashchange', route);
window.addEventListener('beforeunload', (e) => { if (S.dirty) { e.preventDefault(); e.returnValue = ''; } });
window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); const b = $('#save-btn'); if (b && !b.disabled && !b.hidden) b.click(); } });

if (S.token) boot(); else showLogin();
