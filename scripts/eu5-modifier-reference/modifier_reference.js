const { createApp, ref, reactive, computed, watch, nextTick, onMounted, markRaw } = Vue;
const { computePosition, offset, flip, shift } = FloatingUIDOM;

const DATA = JSON.parse(document.getElementById('eu5-data').textContent);
const MODS = markRaw(DATA.mods);
const SRC = markRaw(DATA.src);
const ST = DATA.stats;
const PAGE = 150;

// --- formatting ---------------------------------------------------------------
const norm = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const humanize = s => (s.charAt(0).toUpperCase() + s.slice(1)).replace(/_/g, ' ');
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const raw = v => String(+v.toFixed(6));
const fnum = v => { const s = raw(v); return s === '-0' ? '0' : s.replace('-', '−'); };
const sign = v => (v > 0 ? '+' : '');
const collator = new Intl.Collator('en', { sensitivity: 'base', numeric: true, ignorePunctuation: true });

function disp(v, t) {
	if (t === 'p') return sign(v) + fnum(+(v * 100).toPrecision(6)) + '%';
	if (t === 'a') return sign(v) + fnum(v) + '%';
	return sign(v) + fnum(v);
}
function tone(v, g) {
	if (g === 'neutral' || v === 0) return 'neu';
	return (v > 0) === (g !== 'bad') ? 'pos' : 'neg';
}
const TONE_TEXT = { pos: 'text-success-600 dark:text-success-300', neg: 'text-error-600 dark:text-error-300', neu: 'text-foreground' };
const TONE_BG = { pos: 'bg-success', neg: 'bg-error', neu: 'bg-muted' };

function summary(m) {
	if (m.u) return m.mn[0] === m.mx[0] ? disp(m.mn[0], m.t) : `${disp(m.mn[0], m.t)} to ${disp(m.mx[0], m.t)}`;
	if (m.fl) return `Granted ${m.fl}×`;
	if (m.scn) return 'Scaled only';
	if (m.dyn) return 'Computed only';
	if (m.b !== undefined) return 'Base value';
	return 'Unused';
}

// --- data prep ------------------------------------------------------------------
const byKey = new Map();
for (const [i, m] of MODS.entries()) {
	m.i = i;
	m.uses = (m.u || 0) + (m.scn || 0) + (m.dyn || 0) + (m.fl || 0);
	m.hk = norm(m.k.replace(/_/g, ' '));
	m.hn = norm(m.n);
	m.hd = norm(m.d);
	m.grants = [];
	byKey.set(m.k, m);
}
for (const [i, s] of SRC.entries()) {
	s.i = i;
	s.title = s.name || humanize(s.obj);
	s.hn = norm(`${s.title} ${s.obj.replace(/_/g, ' ')}`);
	s.hk = norm(s.kind);
	s.hf = norm(s.file);
	for (const [m, v, kind = 'f', note] of s.g) MODS[m].grants.push({ s, v, kind, note });
}

const TOPICS = [...new Set(MODS.map(m => m.tp))].sort((a, b) => (a === 'Other') - (b === 'Other') || collator.compare(a, b));
const KINDS = [...new Set(SRC.map(s => s.kind))].sort(collator.compare);
const SYSTEMS = (() => {
	const n = new Map();
	for (const m of MODS) for (const s of m.sy) n.set(s, (n.get(s) || 0) + 1);
	return [...n].map(([name, count]) => ({ name, n: count })).sort((a, b) => b.n - a.n);
})();

const STOP = new Set(['modifier', 'global', 'monthly', 'of', 'the', 'to', 'for', 'from', 'in', 'and', 'with', 'on', 'by', 'is', 'can', 'allow', 'allowed']);
const docFreq = new Map();
for (const m of MODS) {
	m.tok = [...new Set(m.k.split('_').filter(w => w.length > 1 && !STOP.has(w)))];
	for (const w of m.tok) docFreq.set(w, (docFreq.get(w) || 0) + 1);
}
const weight = new Map([...docFreq].map(([w, n]) => [w, Math.log(MODS.length / n)]));

function related(m) {
	const mine = new Set(m.tok);
	const wm = m.tok.reduce((s, w) => s + weight.get(w), 0);
	const out = [];
	for (const o of MODS) {
		if (o === m) continue;
		let shared = 0, wo = 0;
		for (const w of o.tok) {
			wo += weight.get(w);
			if (mine.has(w)) shared += weight.get(w);
		}
		if (!shared) continue;
		const score = shared / (wm + wo - shared) + (o.tp === m.tp ? 0.05 : 0);
		if (score >= 0.3) out.push([score, o]);
	}
	return out.sort((a, b) => b[0] - a[0]).slice(0, 10).map(x => x[1]);
}

// --- search ---------------------------------------------------------------------
function scoreMod(m, q, words) {
	let s = 0;
	for (const w of words) {
		if (m.hk.includes(w)) s += 3;
		else if (m.hn.includes(w)) s += 2;
		else if (m.hd.includes(w)) s += 0.5;
		else return 0;
	}
	if (m.hk === q) s += 100;
	else if (m.hk.startsWith(q)) s += 40;
	if (m.hn === q) s += 80;
	else if (m.hn.startsWith(q)) s += 30;
	if (m.hk.includes(q) || m.hn.includes(q)) s += 10;
	return s + Math.log2(1 + m.uses);
}
function scoreSrc(s, q, words) {
	let sc = 0;
	for (const w of words) {
		if (s.hn.includes(w)) sc += 2;
		else if (s.hk.includes(w) || s.hf.includes(w)) sc += 0.5;
		else return 0;
	}
	if (s.hn.startsWith(q)) sc += 30;
	return sc;
}
function modPasses(m, f, system) {
	if (f.type && (f.type === 'p' ? m.t !== 'p' && m.t !== 'a' : m.t !== f.type)) return false;
	if (f.dir && m.g !== f.dir) return false;
	if (f.scope && m.c !== f.scope) return false;
	if (f.content && (f.content === 'dlc') !== !!m.x) return false;
	if (f.usage === 'range' && !m.u) return false;
	if (f.usage === 'used' && !m.uses) return false;
	if (f.usage === 'unused' && m.uses) return false;
	return !system || m.sy.includes(system);
}

// --- shared state ---------------------------------------------------------------
const query = ref('');
const q = computed(() => norm(query.value.replace(/_/g, ' ')).trim().replace(/\s+/g, ' '));
const words = computed(() => (q.value ? q.value.split(' ') : []));
const markRx = computed(() =>
	words.value.length ? new RegExp(`(${words.value.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'i') : null,
);
function hl(text) {
	const rx = markRx.value;
	if (!rx) return esc(text);
	return String(text).split(rx).map((p, i) => (i % 2 ? `<mark>${esc(p)}</mark>` : esc(p))).join('');
}

const copied = ref('');
let copyTimer;
async function copy(text) {
	try {
		await navigator.clipboard.writeText(text);
	} catch (_) {
		const t = document.createElement('textarea');
		t.value = text;
		document.body.append(t);
		t.select();
		try {
			document.execCommand('copy');
		} catch (_) {}
		t.remove();
	}
	copied.value = text;
	clearTimeout(copyTimer);
	copyTimer = setTimeout(() => (copied.value = ''), 1500);
}

// --- components -----------------------------------------------------------------
const ChipGroup = {
	template: '#chip-group-template',
	props: { label: String, options: Array, modelValue: String },
	emits: ['update:modelValue'],
};

const BackLink = { template: '#back-link-template', props: { view: String } };

const SourceLink = {
	template: '#source-link-template',
	props: { s: Object },
	setup(props) {
		return { tipText: computed(() => `${props.s.obj}\n${props.s.file}`) };
	},
};

const SourceList = {
	template: '#source-list-template',
	props: { sources: Array, initial: { type: Number, default: 4 } },
	setup(props) {
		const open = ref(false);
		const visible = computed(() => (open.value ? props.sources : props.sources.slice(0, props.initial)));
		return { open, visible };
	},
};

const ValueRuler = {
	template: '#value-ruler-template',
	props: { mod: Object, values: Array },
	emits: ['pick'],
	setup(props) {
		const lo = computed(() => Math.min(props.values[0].v, 0));
		const hi = computed(() => Math.max(props.values.at(-1).v, 0));
		const pos = v => ((v - lo.value) / (hi.value - lo.value || 1)) * 100;
		const ticks = computed(() => {
			const { mod, values } = props;
			const most = Math.max(...values.map(e => e.n));
			const top = values.find(e => e.n === most);
			return values.map(e => {
				const names = e.srcs.slice(0, 3).map(s => s.title).join(', ');
				const more = e.srcs.length > 3 ? `, +${e.srcs.length - 3} more` : '';
				const written = mod.t === 'f' ? '' : ` (written ${fnum(e.v)})`;
				return {
					v: e.v,
					x: pos(e.v),
					h: 8 + 32 * Math.sqrt(e.n / most),
					bg: TONE_BG[tone(e.v, mod.g)],
					top: e === top,
					tip: `${disp(e.v, mod.t)}${written}\nUsed ${e.n}×: ${names}${more}`,
					label: `${disp(e.v, mod.t)}, used ${e.n} times`,
				};
			});
		});
		return {
			ticks,
			lo,
			hi,
			top: computed(() => ticks.value.find(t => t.top)),
			zero: computed(() => (lo.value < 0 && hi.value > 0 ? pos(0) : null)),
			clampLabel: x => Math.min(Math.max(x, 10), 90),
			disp,
			toneText: v => TONE_TEXT[tone(v, props.mod.g)],
		};
	},
};

const TYPE_TEXT = {
	p: 'Percent. Write 0.05 for +5%.',
	a: 'Percent, written as a whole number. Write 5 for +5%.',
	f: 'A plain number, shown as written.',
	b: 'Yes/no. Write it as = yes.',
};
const SCOPE_TEXT = { country: 'The country', unit: "All of the country's units", all: 'Any scope, the country included' };
const FORMAT_TEXT = { FormatGold: 'gold', FormatManPower: 'manpower', FormatPopCaps: 'population' };

const ModDetail = {
	template: '#mod-detail-template',
	props: { mod: Object },
	emits: ['topic'],
	setup(props) {
		const picked = ref(null);
		watch(() => props.mod, () => (picked.value = null));

		const d = computed(() => {
			const fixed = new Map();
			const out = { scaled: [], dyn: [], flags: [], base: [] };
			for (const g of props.mod.grants) {
				if (g.kind === 'f') {
					let e = fixed.get(g.v);
					if (!e) fixed.set(g.v, (e = { v: g.v, n: 0, srcs: [], via: [] }));
					e.n++;
					if (!e.srcs.includes(g.s)) e.srcs.push(g.s);
					if (g.note && !e.via.includes(g.note)) e.via.push(g.note);
				} else if (g.kind === 's') out.scaled.push(g);
				else if (g.kind === 'd') out.dyn.push(g);
				else if (g.kind === 'b') out.base.push(g);
				else if (!out.flags.includes(g.s)) out.flags.push(g.s);
			}
			out.scaled.sort((a, b) => a.v - b.v);
			out.values = [...fixed.values()].sort((a, b) => a.v - b.v);
			out.related = related(props.mod);
			return out;
		});

		const facts = computed(() => {
			const m = props.mod, df = m.df || {};
			const f = [{ label: 'Value', text: TYPE_TEXT[m.t] }];
			if (m.t === 'b') f.push({ label: 'Shown as', text: { good: 'Good', bad: 'Bad', neutral: 'Neutral' }[m.g] || 'Good' });
			else if (m.g === 'good') f.push({ label: 'Better when', text: 'Higher', cls: TONE_TEXT.pos });
			else if (m.g === 'bad') f.push({ label: 'Better when', text: 'Lower', cls: TONE_TEXT.neg });
			else f.push({ label: 'Better when', text: 'Neither: the game shows it in a neutral colour' });
			f.push({ label: 'Applies to', text: SCOPE_TEXT[m.c] });
			f.push({ label: 'Topic', text: m.tp, topic: true });
			f.push({ label: 'From', text: m.x || 'Base game', cls: m.x ? 'text-tertiary-strong' : '' });
			const limits = [];
			if (df.z1) limits.push('The total is kept between 0 and 1');
			if (df.min !== undefined && df.max !== undefined) limits.push(`The total stays between ${fnum(df.min)} and ${fnum(df.max)}`);
			else if (df.min !== undefined) limits.push(`The total never goes below ${fnum(df.min)}`);
			else if (df.max !== undefined) limits.push(`The total never goes above ${fnum(df.max)}`);
			if (limits.length) f.push({ label: 'Limits', text: limits.join('. ') });
			const display = [];
			if (df.fmt) display.push(`as ${FORMAT_TEXT[df.fmt] || df.fmt}`);
			if (df.dec === 0) display.push('as a whole number');
			else if (df.dec !== undefined) display.push(`with ${df.dec} decimals`);
			if (display.length) f.push({ label: 'Shown', text: humanize(display.join(', ')) });
			if (df.pop) f.push({ label: 'Scaling', text: 'Scales with population' });
			if (df.bias) f.push({ label: 'Bias', text: `Counts as ${df.bias.join(' and ')} bias` });
			if (df.ai) f.push({ label: 'AI', text: 'Marked as an AI modifier in its definition' });
			if (df.hid) f.push({ label: 'In game', text: 'Hidden from the government modifiers tab' });
			return f;
		});

		const line = computed(() => {
			const m = props.mod;
			if (m.t === 'b') return `${m.k} = yes`;
			const v = m.top?.[0]?.[0] ?? (typeof m.b === 'number' ? m.b : { p: 0.1, a: 10, f: 1 }[m.t]);
			return `${m.k} = ${raw(v)}`;
		});

		const usageText = computed(() => {
			const m = props.mod, { values, scaled, dyn, flags, base } = d.value;
			const p = [];
			if (values.length) {
				const n = values.reduce((s, e) => s + e.n, 0);
				const top = values.reduce((a, e) => (e.n > a.n ? e : a));
				if (values.length === 1) p.push(`Always ${disp(top.v, m.t)} where it's written with a fixed value (${n}×).`);
				else p.push(`Written with a fixed value ${n} times, from ${disp(values[0].v, m.t)} to ${disp(values.at(-1).v, m.t)}. Most often ${disp(top.v, m.t)} (${top.n}×).`);
			}
			if (scaled.length) p.push(`${scaled.length} ${scaled.length === 1 ? 'use is' : 'uses are'} scaled at runtime.`);
			if (dyn.length) p.push(`${dyn.length} ${dyn.length === 1 ? 'use is' : 'uses are'} computed from game state.`);
			if (flags.length) p.push(`Granted by ${flags.length} ${flags.length === 1 ? 'source' : 'sources'}.`);
			if (base.length) p.push('Every country starts with a base value.');
			return p.join(' ') || 'Not used anywhere in vanilla script.';
		});

		function pickValue(v) {
			picked.value = v;
			nextTick(() => document.getElementById(`value-${v}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }));
		}

		return {
			d,
			facts,
			line,
			usageText,
			picked,
			pickValue,
			copy,
			copied,
			fnum,
			disp,
			toneText: v => TONE_TEXT[tone(v, props.mod.g)],
		};
	},
};

const SrcDetail = {
	template: '#src-detail-template',
	props: { src: Object },
	setup(props) {
		const rows = computed(() =>
			props.src.g
				.map(([mi, v, kind = 'f', note]) => {
					const m = MODS[mi];
					const has = v !== null && v !== undefined;
					const row = { m, written: '—', shown: '', note: '', code: '', tone: has ? TONE_TEXT[tone(v, m.g)] : '' };
					if (kind === 'y') Object.assign(row, { written: 'yes', note: 'Yes/no flag' });
					else if (has) Object.assign(row, { written: fnum(v), shown: m.t === 'f' ? '' : disp(v, m.t) });
					if (kind === 's') row.note = humanize(note);
					else if (kind === 'd') Object.assign(row, { note: 'Computed from game state:', code: note });
					else if (kind === 'b') Object.assign(row, { note: 'Base value for every country', code: note || '' });
					else if (kind === 'f' && note) Object.assign(row, { note: 'Written as', code: note });
					return row;
				})
				.sort((a, b) => collator.compare(a.m.n, b.m.n)),
		);
		return { rows };
	},
};

const Overview = {
	template: '#overview-template',
	props: { view: String },
	setup() {
		return {
			stats: ST,
			sourceCount: SRC.length,
			mostUsed: [...MODS].sort((a, b) => b.uses - a.uses).slice(0, 12),
			biggestSources: [...SRC].sort((a, b) => b.g.length - a.g.length).slice(0, 12),
		};
	},
};

// --- app ------------------------------------------------------------------------
function parseHash() {
	const m = location.hash.match(/^#\/(modifiers|sources)(?:\/(.+))?$/);
	return m ? { view: m[1], id: decodeURIComponent(m[2] || '') } : { view: 'modifiers', id: '' };
}
const wide = () => matchMedia('(min-width: 768px)').matches;

const app = createApp({
	template: '#app-template',
	setup() {
		const route = ref(parseHash());
		window.addEventListener('hashchange', () => (route.value = parseHash()));
		const view = computed(() => route.value.view);
		const otherView = computed(() => (view.value === 'modifiers' ? 'sources' : 'modifiers'));
		const mod = computed(() => (view.value === 'modifiers' && route.value.id ? byKey.get(route.value.id) || null : null));
		const src = computed(() => (view.value === 'sources' && route.value.id ? SRC[+route.value.id] || null : null));
		const current = computed(() => mod.value || src.value);

		const drawer = ref(false);
		const topic = ref('');
		const kind = ref('');
		const system = ref('');
		const filters = reactive({ type: '', dir: '', scope: '', content: '', usage: '' });
		const srcContent = ref('');
		const sorts = reactive({ modifiers: 'name', sources: 'name' });
		const sort = computed({ get: () => sorts[view.value], set: v => (sorts[view.value] = v) });
		watch(
			() => !!q.value,
			has => {
				for (const v of ['modifiers', 'sources']) {
					if (has && sorts[v] === 'name') sorts[v] = 'match';
					if (!has && sorts[v] === 'match') sorts[v] = 'name';
				}
			},
		);

		const contentOptions = [['', 'Any'], ['base', 'Base game'], ['dlc', 'DLC']];
		const modFilters = [
			{ id: 'type', label: 'Value type', options: [['', 'Any'], ['p', 'Percent'], ['f', 'Number'], ['b', 'Yes/no']] },
			{ id: 'dir', label: 'Better when', options: [['', 'Any'], ['good', 'Higher'], ['bad', 'Lower']] },
			{ id: 'scope', label: 'Applies to', options: [['', 'Any'], ['country', 'Country'], ['unit', 'Units'], ['all', 'Every scope']] },
			{ id: 'content', label: 'Content', options: contentOptions },
			{ id: 'usage', label: 'In vanilla', options: [['', 'Any'], ['range', 'Has a range'], ['used', 'Used'], ['unused', 'Never used']] },
		];
		const filtersActive = computed(() =>
			view.value === 'modifiers' ? !!system.value || Object.values(filters).some(Boolean) : !!srcContent.value,
		);
		function clearFilters() {
			if (view.value === 'sources') srcContent.value = '';
			else {
				system.value = '';
				for (const k in filters) filters[k] = '';
			}
		}

		const modBase = computed(() => {
			const ws = words.value, qq = q.value, out = [];
			for (const m of MODS) {
				if (!modPasses(m, filters, system.value)) continue;
				const s = ws.length ? scoreMod(m, qq, ws) : 1;
				if (s > 0) out.push([m, s]);
			}
			return out;
		});
		const srcBase = computed(() => {
			const ws = words.value, qq = q.value, out = [];
			for (const s of SRC) {
				if (srcContent.value && (srcContent.value === 'dlc') !== !!s.dlc) continue;
				const sc = ws.length ? scoreSrc(s, qq, ws) : 1;
				if (sc > 0) out.push([s, sc]);
			}
			return out;
		});

		function counted(base, names, key, allLabel) {
			const n = new Map(names.map(x => [x, 0]));
			for (const [item] of base) n.set(item[key], n.get(item[key]) + 1);
			return [{ name: '', label: allLabel, n: base.length }, ...names.map(x => ({ name: x, label: x, n: n.get(x) }))];
		}
		const topicCounts = computed(() => counted(modBase.value, TOPICS, 'tp', 'All topics'));
		const kindCounts = computed(() => counted(srcBase.value, KINDS, 'kind', 'All types').filter(k => k.n || !k.name || k.name === kind.value));

		const sorters = {
			match: (a, b) => b[1] - a[1] || collator.compare(a[0].n ?? a[0].title, b[0].n ?? b[0].title),
			name: (a, b) => collator.compare(a[0].n ?? a[0].title, b[0].n ?? b[0].title),
			key: (a, b) => collator.compare(a[0].k, b[0].k),
			uses: (a, b) => (b[0].uses ?? b[0].g.length) - (a[0].uses ?? a[0].g.length) || collator.compare(a[0].n ?? a[0].title, b[0].n ?? b[0].title),
		};
		const list = computed(() => {
			const isMods = view.value === 'modifiers';
			const pick = isMods ? topic.value : kind.value;
			const items = (isMods ? modBase.value : srcBase.value).filter(([x]) => !pick || (isMods ? x.tp : x.kind) === pick);
			return items.sort(sorters[sort.value] || sorters.name).map(x => x[0]);
		});

		const limit = ref(PAGE);
		const shown = computed(() => list.value.slice(0, limit.value));
		const listEl = ref(null);
		const sentinel = ref(null);
		const detailEl = ref(null);
		const searchEl = ref(null);
		watch(list, () => {
			limit.value = PAGE;
			if (listEl.value) listEl.value.scrollTop = 0;
		});
		watch(current, c => {
			if (detailEl.value) detailEl.value.scrollTop = 0;
			document.title = c ? `${c.n || c.title} – EU5 modifiers` : 'EU5 Country Modifiers';
		});

		const crossCount = computed(() => {
			const ws = words.value, qq = q.value;
			if (!ws.length) return 0;
			let n = 0;
			if (view.value === 'modifiers') for (const s of SRC) n += scoreSrc(s, qq, ws) > 0;
			else for (const m of MODS) n += scoreMod(m, qq, ws) > 0;
			return n;
		});

		const countText = computed(() => {
			const n = list.value.length, many = n !== 1;
			if (view.value === 'modifiers') return `${n.toLocaleString()} ${many ? 'modifiers' : 'modifier'}${topic.value ? ` in ${topic.value}` : ''}`;
			return `${n.toLocaleString()} ${many ? 'sources' : 'source'}${kind.value ? `: ${kind.value}` : ''}`;
		});
		const emptyText = computed(() => {
			const what = view.value === 'modifiers' ? 'modifier' : 'source';
			const where = view.value === 'modifiers' ? topic.value : kind.value;
			let t = query.value.trim() ? `No ${what} matches “${query.value.trim()}”` : `No ${what} matches`;
			if (where) t += ` in ${where}`;
			if (filtersActive.value) t += ' with these filters';
			return `${t}.`;
		});

		function pickTopic(name) {
			topic.value = name;
			drawer.value = false;
			if (!wide()) location.hash = '#/modifiers';
		}
		function pickKind(name) {
			kind.value = name;
			drawer.value = false;
		}

		function idOf(item) {
			return view.value === 'modifiers' ? encodeURIComponent(item.k) : String(item.i);
		}
		function move(step) {
			const items = list.value;
			if (!items.length) return;
			const at = items.indexOf(current.value);
			const next = at < 0 ? (step > 0 ? 0 : items.length - 1) : Math.min(items.length - 1, Math.max(0, at + step));
			if (next >= limit.value) limit.value = next + 50;
			history.replaceState(null, '', `#/${view.value}/${idOf(items[next])}`);
			route.value = parseHash();
			nextTick(() => document.getElementById(`row-${next}`)?.scrollIntoView({ block: 'nearest' }));
		}
		function onSearchKey(e) {
			if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
				e.preventDefault();
				move(e.key === 'ArrowDown' ? 1 : -1);
			} else if (e.key === 'Enter') {
				e.preventDefault();
				if (!current.value) move(1);
				else nextTick(() => detailEl.value?.querySelector('h1')?.focus());
			} else if (e.key === 'Escape' && query.value) {
				e.stopPropagation();
				query.value = '';
			}
		}

		const theme = ref(document.documentElement.dataset.theme || 'light');
		watch(theme, t => {
			document.documentElement.dataset.theme = t;
			try {
				localStorage.setItem('eu5-ref-theme', t);
			} catch (_) {}
		});

		const tip = reactive({ open: false, text: '' });
		const tipEl = ref(null);
		let tipFor = null;
		function showTip(el) {
			tipFor?.removeAttribute('aria-describedby');
			tipFor = el;
			tip.text = el.dataset.tip;
			tip.open = true;
			el.setAttribute('aria-describedby', 'tip');
			nextTick(() => {
				if (tipFor !== el || !tipEl.value) return;
				computePosition(el, tipEl.value, {
					strategy: 'fixed',
					placement: 'top',
					middleware: [offset(8), flip(), shift({ padding: 8 })],
				}).then(({ x, y }) => Object.assign(tipEl.value.style, { left: `${x}px`, top: `${y}px` }));
			});
		}
		function hideTip() {
			tipFor?.removeAttribute('aria-describedby');
			tipFor = null;
			tip.open = false;
		}

		onMounted(() => {
			const io = new IntersectionObserver(es => es.some(e => e.isIntersecting) && (limit.value += 250), {
				root: listEl.value,
				rootMargin: '400px',
			});
			watch(sentinel, (el, old) => {
				if (old) io.unobserve(old);
				if (el) io.observe(el);
			}, { immediate: true });

			document.addEventListener('pointerover', e => {
				const el = e.target.closest?.('[data-tip]');
				if (el && el !== tipFor) showTip(el);
				else if (!el && tipFor) hideTip();
			});
			document.addEventListener('focusin', e => {
				const el = e.target.closest?.('[data-tip]');
				if (el) showTip(el);
				else if (tipFor) hideTip();
			});
			document.addEventListener('scroll', hideTip, true);
			document.addEventListener('keydown', e => {
				const typing = e.target.closest?.('input, textarea, select, [contenteditable]');
				if ((e.key === '/' && !typing) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey))) {
					e.preventDefault();
					searchEl.value?.focus();
					searchEl.value?.select();
				} else if (e.key === 'Escape') {
					drawer.value = false;
					hideTip();
				}
			});

			const at = list.value.indexOf(current.value);
			if (at >= 0) {
				if (at >= limit.value) limit.value = at + 50;
				nextTick(() => document.getElementById(`row-${at}`)?.scrollIntoView({ block: 'center' }));
			}
			if (current.value) document.title = `${current.value.n || current.value.title} – EU5 modifiers`;
		});

		return {
			views: [
				{ id: 'modifiers', label: 'Modifiers', count: MODS.length.toLocaleString() },
				{ id: 'sources', label: 'Sources', count: SRC.length.toLocaleString() },
			],
			view,
			otherView,
			mod,
			src,
			current,
			query,
			drawer,
			topic,
			kind,
			system,
			systems: SYSTEMS,
			filters,
			modFilters,
			contentOptions,
			srcContent,
			filtersActive,
			clearFilters,
			sort,
			topicCounts,
			kindCounts,
			list,
			shown,
			limit,
			listEl,
			sentinel,
			detailEl,
			searchEl,
			crossCount,
			countText,
			emptyText,
			pickTopic,
			pickKind,
			onSearchKey,
			theme,
			tip,
			tipEl,
			copied,
			hl,
			summary,
		};
	},
});
const components = { ChipGroup, BackLink, SourceLink, SourceList, ValueRuler, ModDetail, SrcDetail, Overview };
for (const [name, c] of Object.entries(components)) app.component(name, c);
app.mount('#app');
