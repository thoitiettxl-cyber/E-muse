#!/usr/bin/env bash
# linkage check: mcp-tool-docs — every tool implemented in ToolDefs.kt must be
# documented in docs/TOOLS.md (and vice versa).
#
# E-Muse's "add a new tool" checklist (AGENTS.md) requires updating
# docs/TOOLS.md for every ToolDef. When it drifts, users/Pi call tools that
# are undocumented (or docs promise tools that don't exist).
#
# A documented tool carrying `<!-- NO_DOC_CHECK: <reason> -->` on the line
# directly above its table row is exempted (deliberate escape hatch; reason
# required, echoed in the PASS report). There are no such exemptions today.
#
# Test hooks (environment overrides; defaults shown):
#   TOOLS_MD  — docs/TOOLS.md to parse
#   TOOLDEFS  — ToolDefs.kt to scan

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_MD="${TOOLS_MD:-$REPO_ROOT/docs/TOOLS.md}"
TOOLDEFS="${TOOLDEFS:-$REPO_ROOT/app/src/main/java/io/github/thoitiet/emuse/mcp/ToolDefs.kt}"

if [[ ! -f "$TOOLS_MD" ]]; then
    echo "FAIL: tools doc not found: $TOOLS_MD" >&2
    exit 2
fi
if [[ ! -f "$TOOLDEFS" ]]; then
    echo "FAIL: ToolDefs.kt not found: $TOOLDEFS" >&2
    exit 2
fi

export TOOLS_MD TOOLDEFS

python3 - <<'PYEOF'
import os
import re
import sys

tools_md = os.environ["TOOLS_MD"]
tooldefs = os.environ["TOOLDEFS"]

# --- Parse site A: documented tools in docs/TOOLS.md.
# Table rows look like: | `device_list` | query | - | ... |
# ONLY the main table under "# MCP Tools E-Muse" counts — the later
# "## Nhóm quyền" section lists permission GROUPS (terminal_file, ...),
# which are not tools and must not be parsed as such.
# The escape hatch is an HTML comment on the line directly above the row.
doc_lines = open(tools_md, encoding="utf-8").read().splitlines()
end = next((i for i, l in enumerate(doc_lines)
            if l.startswith("## ")), len(doc_lines))
documented = []
for i, line in enumerate(doc_lines[:end]):
    m = re.match(r"^\|\s*`([a-z0-9_]+)`\s*\|", line)
    if not m:
        continue
    reason = None
    if i > 0:
        hm = re.match(r"^\s*<!--\s*NO_DOC_CHECK:\s*(.+?)\s*-->\s*$",
                      doc_lines[i - 1])
        if hm:
            reason = hm.group(1)
    documented.append({"name": m.group(1), "exempt_reason": reason})

if not documented:
    print("FAIL: no documented tools parsed from %s" % tools_md,
          file=sys.stderr)
    sys.exit(2)

# --- Scan site B: implemented tools in ToolDefs.kt.
# Each tool is `ToolDef(\n        "<name>",` — the first string literal arg.
src = open(tooldefs, encoding="utf-8").read()
implemented = set(re.findall(r"ToolDef\(\s*\"([a-z0-9_]+)\"", src))

# --- Compare both directions.
undocumented = [d for d in documented
                if d["name"] not in implemented and not d["exempt_reason"]]
exempted = [(d["name"], d["exempt_reason"]) for d in documented
            if d["name"] not in implemented and d["exempt_reason"]]
doc_names = {d["name"] for d in documented}
unlisted = sorted(n for n in implemented if n not in doc_names)

offenders = undocumented + [{"name": n, "exempt_reason": None}
                            for n in unlisted]

if offenders:
    print("FAIL: tool/docs drift (%d):" % len(offenders))
    for d in sorted(undocumented, key=lambda x: x["name"]):
        print("  - documented but not implemented: %s" % d["name"])
    for n in unlisted:
        print("  - implemented but not documented: %s" % n)
    sys.exit(1)

print("PASS: %d tools documented+implemented in sync; %d marked NO_DOC_CHECK, "
      "0 offenders" % (len(documented), len(exempted)))
for name, reason in sorted(exempted):
    print("  exempt: %s (%s)" % (name, reason))
PYEOF
