"""Build an EU5 country-modifier reference (HTML + TXT) from the installed game.

usage: python build_modifier_reference.py <game folder> <output .html> [<output .txt>]
See README.md next to this file.
"""
import ast, collections, datetime, glob, json, operator, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pdxparse import parse

G = sys.argv[1].replace(chr(92), '/').rstrip('/')
OUT_HTML = sys.argv[2]
OUT_TXT = sys.argv[3] if len(sys.argv) > 3 else None

INCLUDED = ('country', 'unit', 'all')
NUM = re.compile(r'-?\d+(?:\.\d+)?')


def read(p):
    return open(p, encoding='utf-8-sig', errors='replace').read()


def rel(p):
    return p.replace(chr(92), '/').split(G + '/', 1)[-1]


def dlc_of(path):
    m = re.search(r'(?:^|/)dlc/D\d+_([a-z0-9_]+)/', rel(path))
    if not m:
        return None
    words = m.group(1).split('_')
    return ' '.join(w if i and w in ('the', 'of', 'and', 'a') else w.capitalize() for i, w in enumerate(words))


def game_files(pattern):
    return sorted(glob.glob(f'{G}/{pattern}', recursive=True) + glob.glob(f'{G}/dlc/*/{pattern}', recursive=True))


# --- modifier definitions -------------------------------------------------
defs = {}
for f in game_files('main_menu/common/modifier_type_definitions/*.txt'):
    for k, op, body in parse(read(f)):
        if not k or not isinstance(body, list):
            continue
        flat = {kk: vv for kk, _, vv in body if kk and not isinstance(vv, list)}
        gd = next((vv for kk, _, vv in body if kk == 'game_data' and isinstance(vv, list)), [])
        cat = next((vv for kk, _, vv in gd if kk == 'category'), 'all')
        defs[k] = {
            'category': cat,
            'type': 'b' if flat.get('boolean') == 'yes' else 'p' if flat.get('percent') == 'yes'
                    else 'a' if flat.get('already_percent') == 'yes' else 'f',
            'color': flat.get('color', 'good'),
            'dlc': dlc_of(f),
            'file': rel(f),
        }
WANT = {k for k, d in defs.items() if d['category'] in INCLUDED}

# --- localization ---------------------------------------------------------
loc = {}
for f in glob.glob(f'{G}/**/localization/english/**/*.yml', recursive=True):
    for m in re.finditer(r'^\s+([A-Za-z0-9_.\-]+):\d*\s*"(.*)"\s*$', read(f), re.M):
        loc.setdefault(m.group(1), m.group(2))


def humanize(s):
    return s.replace('_', ' ').strip()


def resolve_loc(key, depth=0):
    return clean(loc[key], depth + 1) if key in loc and depth < 4 else humanize(key)


def clean(text, depth=0):
    text = text.replace('\\n', ' ')
    text = re.sub(r'\$([A-Za-z0-9_]+)(\|[^$]*)?\$', lambda m: resolve_loc(m.group(1), depth), text)
    text = re.sub(r'\[([A-Za-z0-9_]+)\|[A-Za-z]+\]', lambda m: humanize(m.group(1)), text)
    text = re.sub(r"\[[^\]]*?'([^']+)'[^\]]*\]", lambda m: resolve_loc(m.group(1), depth), text)
    text = re.sub(r'\[[^\]]*\]', '', text)
    text = re.sub(r'#![^\s]*|#[A-Za-z_]+;?\s?', '', text)
    text = text.replace('@', '')
    return re.sub(r'\s+', ' ', text).strip()


def object_name(key):
    for k in (f'STATIC_MODIFIER_NAME_{key}', key, f'{key}_name', f'{key}.t'):
        if k in loc:
            name = clean(loc[k])
            if name and len(name) < 80:
                return name
    return None


# --- value resolution (script values, @variables, @[math]) ----------------
class Dynamic(Exception):
    pass


BINOPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv}


def eval_math(expr, scope):
    def ev(n):
        if isinstance(n, ast.Constant) and isinstance(n.value, (int, float)):
            return float(n.value)
        if isinstance(n, ast.BinOp) and type(n.op) in BINOPS:
            return BINOPS[type(n.op)](ev(n.left), ev(n.right))
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, (ast.USub, ast.UAdd)):
            v = ev(n.operand)
            return -v if isinstance(n.op, ast.USub) else v
        if isinstance(n, ast.Name):
            return scope.var(n.id)
        raise Dynamic(expr)
    try:
        return ev(ast.parse(expr.strip(), mode='eval').body)
    except (SyntaxError, ZeroDivisionError):
        raise Dynamic(expr)


global_vars = {}


class Scope:
    """@variables visible in one file (falls back to a unique global one)."""

    def __init__(self, raw):
        self.raw, self.cache = raw, {}

    def var(self, name, seen=()):
        if name in self.cache:
            return self.cache[name]
        raw = self.raw.get(name)
        if raw is None:
            if name in global_vars and global_vars[name] is not None:
                return global_vars[name]
            raise Dynamic('@' + name)
        if name in seen:
            raise Dynamic('@' + name)
        v = resolve(raw, self, seen + (name,))
        self.cache[name] = v
        return v


def file_vars(nodes):
    return {k[1:]: v for k, op, v in nodes if k and k.startswith('@') and not isinstance(v, list)}


sv_raw, sv_cache = {}, {}
for f in game_files('*/common/script_values/*.txt'):
    nodes = parse(read(f))
    sc = Scope(file_vars(nodes))
    for k, op, v in nodes:
        if k and not k.startswith('@'):
            sv_raw[k] = (v, sc)


def script_value(name, seen):
    if name in sv_cache:
        if isinstance(sv_cache[name], Dynamic):
            raise sv_cache[name]
        return sv_cache[name]
    if name in seen:
        raise Dynamic(name)
    v, sc = sv_raw[name]
    try:
        sv_cache[name] = resolve(v, sc, seen + (name,))
    except Dynamic:
        sv_cache[name] = Dynamic(name)
        raise sv_cache[name]
    return sv_cache[name]


def resolve(v, scope, seen=()):
    """Return a float, or raise Dynamic when the value depends on game state."""
    if isinstance(v, list):
        acc = None
        for k, op, x in v:
            if k is None and x == '':
                continue
            if k in ('desc', 'format', 'save_temporary_value_as'):
                continue
            if op != '=' or k not in ('value', 'add', 'subtract', 'multiply', 'divide', 'min', 'max', 'round', 'floor', 'ceiling', 'abs'):
                raise Dynamic('computed')
            if k in ('round', 'floor', 'ceiling', 'abs'):
                if x == 'yes' and acc is not None:
                    acc = {'round': round, 'floor': lambda a: float(int(a // 1)), 'ceiling': lambda a: -float(int(-a // 1)), 'abs': abs}[k](acc)
                continue
            n = resolve(x, scope, seen)
            if k == 'value':
                acc = n
            elif acc is None:
                raise Dynamic('computed')
            elif k == 'add':
                acc += n
            elif k == 'subtract':
                acc -= n
            elif k == 'multiply':
                acc *= n
            elif k == 'divide':
                if n == 0:
                    raise Dynamic('computed')
                acc /= n
            elif k == 'min':
                acc = max(acc, n)
            elif k == 'max':
                acc = min(acc, n)
        if acc is None:
            raise Dynamic('computed')
        return acc
    s = v.strip('"')
    if NUM.fullmatch(s):
        return float(s)
    if s.startswith('@['):
        return eval_math(s[2:-1], scope)
    if s.startswith('@'):
        return scope.var(s[1:], seen)
    if s in sv_raw:
        return script_value(s, seen)
    raise Dynamic(s)


# global @variables (used only when a file does not define its own)
for f in game_files('**/common/**/*.txt'):
    text = read(f)
    if '@' not in text:
        continue
    for k, v in file_vars(parse(text)).items():
        global_vars[k] = None if k in global_vars else v
for k, v in list(global_vars.items()):
    try:
        global_vars[k] = resolve(v, Scope({})) if v is not None else None
    except Dynamic:
        global_vars[k] = None

# --- scan the game for modifier usage -------------------------------------
SKIP_DIRS = ('/localization/', '/gfx/', '/gui/', '/map_data/', '/fonts/', '/music/', '/sound/',
             'modifier_type_definitions', 'modifier_icons', 'game_concepts', 'trigger_localization',
             'effect_localization', 'attribute_columns', 'alert_descriptions', '/script_values/',
             'common/tests/', 'ai_diplochance', 'customizable_localization', 'scripted_guis')
SKIP_PARENTS = re.compile(r'^(diplo_chance.*|create_character|ai_will_do|ai_chance|weight|limit|trigger|potential)$')
DEBUG_OBJECT = re.compile(r'(^qa_|debug|^test_)')
ESTATE_SCALE = {
    'satisfaction': 'multiplied by estate satisfaction above the low threshold',
    'power': 'scaled by estate power',
    'high_power': 'multiplied by estate power above the high threshold',
    'low_power': 'multiplied by how far estate power is below the low threshold',
}
KIND_LABEL = {
    'static_modifiers': 'Static modifier', 'advances': 'Advance', 'laws': 'Law / policy',
    'government_reforms': 'Government reform', 'estate_privileges': 'Estate privilege',
    'building_types': 'Building', 'religions': 'Religion', 'auto_modifiers': 'Auto modifier',
    'traits': 'Trait', 'religious_aspects': 'Religious aspect', 'gods': 'God',
    'societal_values': 'Societal value', 'town_rights': 'Town right', 'parliament_issues': 'Parliament issue',
    'disasters': 'Disaster', 'cabinet_actions': 'Cabinet action', 'international_organizations': 'International org.',
    'bureaucracies': 'Bureaucracy', 'subject_types': 'Subject type', 'estates': 'Estate', 'age': 'Age',
    'parliament_types': 'Parliament type', 'chivalric_orders': 'Chivalric order', 'country_ranks': 'Country rank',
    'situations': 'Situation', 'decisions': 'Decision', 'missions': 'Mission', 'government_types': 'Government type',
    'events': 'Event', 'setup': 'Setup', 'religious_schools': 'Religious school', 'regencies': 'Regency',
    'holy_site_types': 'Holy site', 'religious_order_callings': 'Religious order calling',
    'religious_focuses': 'Religious focus', 'hegemons': 'Hegemony', 'employment_systems': 'Employment system',
    'heir_selections': 'Heir selection', 'generic_actions': 'Action', 'ai_personalities': 'AI personality',
    'international_organization_special_statuses': 'Int. org. status', 'international_organization_payments': 'Int. org. payment',
    'formable_countries': 'Formable country', 'country_interactions': 'Country interaction',
    'character_interactions': 'Character interaction', 'location_ranks': 'Location rank', 'topography': 'Terrain',
    'vegetation': 'Vegetation', 'climates': 'Climate', 'unit_types': 'Unit type', 'levies': 'Levy',
    'recruitment_method': 'Recruitment method', 'policies': 'Policy', 'insults': 'Insult',
    'child_educations': 'Child education', 'artist_types': 'Artist', 'colonial_charter_goals': 'Colonial charter',
    'religious_figures': 'Religious figure', 'religious_factions': 'Religious faction', 'resolutions': 'Resolution',
    'scripted_effects': 'Scripted effect', 'on_action': 'On action', 'institution': 'Institution',
    'peace_treaties': 'Peace treaty', 'casus_belli': 'Casus belli', 'wargoals': 'War goal',
}

sources, source_idx = [], {}


def source_id(path, obj):
    r = rel(path)
    parts = r.split('/')
    kind = parts[parts.index('common') + 1] if 'common' in parts else ('events' if 'events' in parts else 'setup' if 'setup' in parts else parts[-2])
    label = KIND_LABEL.get(kind, humanize(kind).capitalize())
    dlc = dlc_of(path)
    key = (r, obj)
    if key not in source_idx:
        source_idx[key] = len(sources)
        sources.append({'kind': label, 'obj': obj, 'name': object_name(obj), 'file': r, 'dlc': dlc})
    return source_idx[key]


occ = collections.defaultdict(list)   # key -> list of occurrence dicts
excluded = collections.Counter()


def scale_term(x, f):
    x = x.strip('"')
    if x == 'scope:maintenance' and '/bureaucracies/' in f:
        return 'bureaucracy funding'
    return humanize(x.replace('scope:', '').replace('modifier:', ''))


def scale_of(nodes, f):
    """Describe a sibling scale / scales_with, e.g. '1 - bureaucracy funding'."""
    for k, op, v in nodes:
        if k not in ('scale', 'scales_with'):
            continue
        if not isinstance(v, list):
            return 'scales with ' + scale_term(v, f)
        expr = None
        for kk, _, x in v:
            sym = {'value': None, 'add': '+', 'subtract': '−', 'multiply': '×', 'divide': '÷'}
            if kk not in sym or isinstance(x, list):
                return 'scales with a computed value'
            term = scale_term(x, f)
            expr = term if sym[kk] is None else f'{expr} {sym[kk]} {term}'
        return f'scales with ({expr})' if expr and ' ' in expr and any(s in expr for s in '+−×÷') else f'scales with {expr or "a computed value"}'
    return None


def walk(nodes, path, f, scope, inherited_scale):
    here_scale = scale_of(nodes, f) or inherited_scale
    parent = path[-1] if path else None
    if parent and '/estates/' in f and parent in ESTATE_SCALE:
        here_scale = ESTATE_SCALE[parent]
    if parent in ('left_modifier', 'right_modifier') and 'societal_values' in f:
        here_scale = 'scales with the societal value slider'
    for k, op, v in nodes:
        if k in WANT and op == '=' and path:
            obj = path[0]
            if SKIP_PARENTS.match(parent or '') or any(SKIP_PARENTS.match(p or '') for p in path):
                excluded['context'] += 1
            elif DEBUG_OBJECT.search(obj):
                excluded['debug'] += 1
            else:
                record(k, v, f, obj, scope, here_scale)
        if isinstance(v, list):
            walk(v, path + [k or '{}'], f, scope, here_scale if path else None)


def record(k, v, f, obj, scope, scale):
    d = defs[k]
    o = {'base': obj == 'country_base_values', 'scale': scale}
    raw = None if isinstance(v, list) else v.strip('"')
    if d['type'] != 'b':
        try:
            o['val'] = round(resolve(v, scope), 6) + 0.0
            if raw is not None and not NUM.fullmatch(raw):
                o['via'] = raw
        except Dynamic:
            o['dyn'] = raw if raw is not None else 'inline computed value'
    o['src'] = source_id(f, obj)
    occ[k].append(o)


scan = [p.replace(chr(92), '/') for p in glob.glob(f'{G}/**/*.txt', recursive=True)]
scan = [p for p in scan if not any(s in p for s in SKIP_DIRS)]
for f in scan:
    nodes = parse(read(f))
    walk(nodes, [], f, Scope(file_vars(nodes)), None)

# --- aggregate --------------------------------------------------------------
records = []
for k in sorted(WANT):
    d = defs[k]
    name_key = f'MODIFIER_TYPE_NAME_{k}'
    rec = {
        'k': k,
        'n': clean(loc[name_key]) if name_key in loc else humanize(k).capitalize(),
        'd': clean(loc.get(f'MODIFIER_TYPE_DESC_{k}', '')),
        'c': d['category'], 't': d['type'], 'g': d['color'], 'x': d['dlc'],
    }
    fixed, scaled, dyn, base, flags = [], [], [], [], []
    for o in occ.get(k, []):
        if d['type'] == 'b':
            flags.append(o)
        elif o['base']:
            base.append(o)
        elif 'dyn' in o:
            dyn.append(o)
        elif o['scale']:
            scaled.append(o)
        else:
            fixed.append(o)
    if fixed:
        by_val = collections.OrderedDict()
        for o in sorted(fixed, key=lambda o: o['val']):
            e = by_val.setdefault(o['val'], {'n': 0, 'src': [], 'via': set()})
            e['n'] += 1
            if o['src'] not in e['src']:
                e['src'].append(o['src'])
            if o.get('via'):
                e['via'].add(o['via'])
        lo = min(fixed, key=lambda o: o['val'])
        hi = max(fixed, key=lambda o: o['val'])
        rec['u'] = len(fixed)
        rec['mn'] = [lo['val'], lo['src']]
        rec['mx'] = [hi['val'], hi['src']]
        rec['top'] = [[v, e['n']] for v, e in sorted(by_val.items(), key=lambda kv: -kv[1]['n'])[:3]]
        rec['vals'] = [[v, e['n'], e['src'][:5], sorted(e['via'])] for v, e in by_val.items()]
    if scaled:
        rec['sc'] = [[o['val'], o['scale'], o['src'], o.get('via')] for o in sorted(scaled, key=lambda o: o['val'])][:40]
        rec['scn'] = len(scaled)
    if dyn:
        rec['dy'] = [[o['dyn'], o['src']] for o in dyn][:20]
        rec['dyn'] = len(dyn)
    if base:
        rec['b'] = base[0].get('val', base[0].get('dyn'))
    if flags:
        rec['fl'] = len(flags)
        rec['fls'] = list(dict.fromkeys(o['src'] for o in flags))[:12]
    records.append(rec)

stats = {
    'total': len(records),
    'country': sum(r['c'] == 'country' for r in records),
    'unit': sum(r['c'] == 'unit' for r in records),
    'all': sum(r['c'] == 'all' for r in records),
    'dlc': sum(bool(r['x']) for r in records),
    'used': sum(bool(r.get('u') or r.get('fl') or r.get('sc')) for r in records),
    'occurrences': sum(len(v) for v in occ.values()),
    'date': datetime.date.today().isoformat(),
    'deffiles': sorted({d['file'] for d in defs.values()}),
}
print('records', stats, 'excluded', dict(excluded), 'sources', len(sources))

# --- HTML -------------------------------------------------------------------
template = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'modifier_reference_template.html'), encoding='utf-8').read()
payload = json.dumps({'mods': records, 'src': sources, 'stats': stats}, ensure_ascii=False, separators=(',', ':'))
payload = payload.replace('</', '<\\/')
with open(OUT_HTML, 'w', encoding='utf-8') as fh:
    fh.write(template.replace('/*__DATA__*/null', payload))

# --- TXT --------------------------------------------------------------------
if OUT_TXT:
    def fnum(v):
        s = ('%.6f' % v).rstrip('0').rstrip('.')
        return '0' if s in ('-0', '') else s

    def src_txt(i):
        s = sources[i]
        return f"{s['kind']} {s['obj']}"

    TYPE = {'b': 'Yes/No flag', 'p': 'Percent (0.05 = +5%)', 'a': 'Percent, whole number (5 = +5%)', 'f': 'Flat number'}
    DIR = {'good': 'Higher is better', 'bad': 'Lower is better', 'neutral': 'Neutral'}
    L = []
    L += ['=' * 100, ' EUROPA UNIVERSALIS V - COUNTRY MODIFIER REFERENCE',
          f" {stats['total']} modifiers ({stats['country']} country, {stats['unit']} unit, {stats['all']} any-scope), generated {stats['date']}",
          ' The HTML version of this file is easier to browse and filter.', '=' * 100, '',
          ' Range = smallest and largest fixed value the base game + DLCs ever use (named script values and',
          ' @variables resolved). Values that are multiplied at runtime (estate power, sliders, scales_with)',
          ' and values computed from game state are listed separately, not counted in the range.', '']
    for cat, title in (('country', 'COUNTRY MODIFIERS'), ('unit', 'UNIT MODIFIERS (apply to all your units)'), ('all', 'ANY-SCOPE MODIFIERS')):
        rs = [r for r in records if r['c'] == cat]
        L += ['=' * 100, f' {title} ({len(rs)})', '=' * 100, '']
        for r in rs:
            L.append(f"{r['k']}  -  {r['n']}")
            L.append('    ' + ' | '.join([TYPE[r['t']], DIR.get(r['g'], 'Neutral') if r['t'] != 'b' else ('Beneficial' if r['g'] == 'good' else 'Harmful' if r['g'] == 'bad' else 'Neutral'), 'DLC: ' + r['x'] if r['x'] else 'Base game']))
            if r.get('u'):
                L.append(f"    Range: {fnum(r['mn'][0])} ({src_txt(r['mn'][1])})  ..  {fnum(r['mx'][0])} ({src_txt(r['mx'][1])})")
                L.append('    Most common: ' + ', '.join(f'{fnum(v)} ({n}x)' for v, n in r['top']) + f"   [{r['u']} fixed uses]")
            if r.get('b') is not None:
                L.append(f"    Base value for every country: {r['b'] if isinstance(r['b'], str) else fnum(r['b'])}")
            if r.get('sc'):
                L.append(f"    Scaled uses ({r['scn']}): " + ', '.join(f"{fnum(v)} {s}" for v, s, _, _ in r['sc'][:3]))
            if r.get('dy'):
                L.append(f"    Computed from game state ({r['dyn']}): " + ', '.join(sorted({x for x, _ in r['dy']}))[:200])
            if r.get('fl'):
                L.append(f"    Granted {r['fl']}x, e.g. " + ', '.join(src_txt(i) for i in r['fls'][:3]))
            if not (r.get('u') or r.get('sc') or r.get('dy') or r.get('fl') or r.get('b') is not None):
                L.append('    Not used anywhere in vanilla script.')
            if r['d']:
                L.append('    ' + r['d'])
            L.append('')
    with open(OUT_TXT, 'w', encoding='utf-8-sig', newline='\r\n') as fh:
        fh.write('\n'.join(L))
