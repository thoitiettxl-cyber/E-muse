#!/usr/bin/env python3
"""Parse possibly-truncated ui_snapshot JSON, keep complete elements."""
import json, re, subprocess, sys

def parse_partial(raw):
    # find elements array start
    m = re.search(r'"elements"\s*:\s*\[', raw)
    if not m: return []
    i = m.end(); els = []
    while i < len(raw):
        # skip whitespace/commas
        while i < len(raw) and raw[i] in ' \t\r\n,': i += 1
        if i >= len(raw) or raw[i] != '{': break
        depth = 0; instr = False; esc = False; j = i
        while j < len(raw):
            c = raw[j]
            if instr:
                if esc: esc = False
                elif c == '\\': esc = True
                elif c == '"': instr = False
            else:
                if c == '"': instr = True
                elif c == '{': depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        try: els.append(json.loads(raw[i:j+1]))
                        except Exception: pass
                        i = j + 1
                        break
            j += 1
        else:
            break  # truncated mid-element
        if j >= len(raw): break
    return els

if __name__ == "__main__":
    raw = open(sys.argv[1]).read()
    els = parse_partial(raw)
    print(f"elements: {len(els)}")
    for e in els:
        txt = ((e.get('text') or '') + ' | ' + (e.get('desc') or '')).strip(' |').replace('\n',' ')
        if txt.strip(): print(e['id'], txt[:80])
