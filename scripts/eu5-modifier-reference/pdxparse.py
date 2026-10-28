"""Small Paradox (Jomini) script parser.

parse(text) -> list of nodes. A node is (key, op, value) for assignments or
(None, None, value) for bare list items. value is a str for scalars or a list
of nodes for blocks.
"""
import re

TOKEN = re.compile(r'''
    (?P<comment>\#[^\n]*)
  | (?P<string>"(?:[^"\\]|\\.)*")
  | (?P<math>@\[[^\]]*\])
  | (?P<op><=|>=|!=|\?=|==|=|<|>)
  | (?P<open>\{)
  | (?P<close>\})
  | (?P<word>[^\s=<>!?{}\#"]+)
''', re.X)


def tokenize(text):
    for m in TOKEN.finditer(text):
        kind = m.lastgroup
        if kind != 'comment':
            yield kind, m.group(kind)


def parse(text):
    toks = list(tokenize(text))
    pos = 0

    def parse_block():
        nonlocal pos
        nodes = []
        while pos < len(toks):
            kind, val = toks[pos]
            if kind == 'close':
                pos += 1
                return nodes
            if kind == 'open':
                pos += 1
                nodes.append((None, None, parse_block()))
                continue
            pos += 1
            if pos < len(toks) and toks[pos][0] == 'op':
                op = toks[pos][1]
                pos += 1
                if pos >= len(toks):
                    break
                vk, vv = toks[pos]
                pos += 1
                if vk == 'open':
                    nodes.append((val, op, parse_block()))
                else:
                    # typed values such as rgb { .. } / hsv { .. }
                    if pos < len(toks) and toks[pos][0] == 'open' and vk == 'word' and vv in ('rgb', 'hsv', 'hsv360', 'list'):
                        pos += 1
                        parse_block()
                    nodes.append((val, op, vv))
            else:
                nodes.append((None, None, val))
        return nodes

    out = []
    while pos < len(toks):
        if toks[pos][0] == 'close':  # stray brace in vanilla file
            pos += 1
            continue
        out.extend(parse_block())
    return out
