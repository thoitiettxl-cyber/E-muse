#!/usr/bin/env bash
# Pre-commit verification for E-muse phases.
# Catches the "basic errors that kept repeating":
#   - P5: missing `import ...exec.SensitiveReadExecutor` in CommandDispatcher.kt
#   - P4: Android API used without the matching <uses-permission> in the manifest
#   - wiring drift between Protocol / ToolDefs / ToolGroups / CommandDispatcher
#
# LIMITATION (by design): this script checks syntax/wiring only. It does NOT
# resolve Kotlin symbols, so an unresolved reference (e.g. a Prefs property
# deleted by one batch while another batch still uses it) passes here and is
# only caught by a real Gradle build. CI still runs :app:assembleRelease,
# which is the true gate.
#
# Usage: scripts/verify-phase.sh [base]   (default base: origin/eta-parity)
set -u
cd "$(dirname "$0")/.."

BASE="${1:-origin/eta-parity}"
FAIL=0
fail() { echo "FAIL: $1"; FAIL=1; }

# A missing base ref used to make the diff below silently empty, so every
# check "passed" on zero files. Fail closed instead.
if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
  echo "FAIL: cannot resolve base ref '$BASE' — fetch it first (e.g. git fetch origin ${BASE#origin/})"
  exit 1
fi

KOTLIN_FILES=$( {
  git diff --name-only --diff-filter=ACM HEAD -- 'app/src/main/java/**/*.kt' 2>/dev/null
  git diff --name-only --diff-filter=ACM "$BASE"...HEAD -- 'app/src/main/java/**/*.kt' 2>/dev/null
} | sort -u | while read -r f; do [ -f "$f" ] && echo "$f"; done )
if [ -z "$KOTLIN_FILES" ]; then
  echo "no Kotlin changes vs $BASE"
fi

# 1) Brace/paren/bracket balance on changed Kotlin files (string/comment aware).
for f in $KOTLIN_FILES; do
  python3 - "$f" <<'EOF'
import sys
src = open(sys.argv[1], encoding="utf-8").read()
pairs = {"{": "}", "(": ")", "[": "]"}
stack = []
i, n = 0, len(src)
line = 1
while i < n:
    c = src[i]
    if c == "\n":
        line += 1; i += 1; continue
    if c == "/" and i + 1 < n and src[i+1] == "/":
        while i < n and src[i] != "\n": i += 1
        continue
    if c == "/" and i + 1 < n and src[i+1] == "*":
        i += 2
        while i + 1 < n and not (src[i] == "*" and src[i+1] == "/"):
            if src[i] == "\n": line += 1
            i += 1
        i += 2; continue
    if c == '"':
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            line += src.count("\n", i, j if j != -1 else n)
            i = n if j == -1 else j + 3; continue
        i += 1
        while i < n and src[i] != '"':
            if src[i] == "\\": i += 1
            if i < n and src[i] == "\n": line += 1
            i += 1
        i += 1; continue
    if c == "'":
        i += 1
        while i < n and src[i] != "'":
            if src[i] == "\\": i += 1
            i += 1
        i += 1; continue
    if c in pairs:
        stack.append((c, line))
    elif c in pairs.values():
        if not stack or pairs[stack[-1][0]] != c:
            print(f"UNBALANCED {sys.argv[1]}:{line}: unexpected '{c}'")
            sys.exit(1)
        stack.pop()
    i += 1
if stack:
    print(f"UNBALANCED {sys.argv[1]}:{stack[-1][1]}: unclosed '{stack[-1][0]}'")
    sys.exit(1)
EOF
  [ $? -ne 0 ] && FAIL=1
done
[ $FAIL -eq 0 ] && echo "OK: braces balanced"

# 2) Every executor class referenced in CommandDispatcher has a matching import.
DISP=app/src/main/java/io/github/thoitiet/emuse/CommandDispatcher.kt
for cls in $(grep -oE '[A-Z][A-Za-z0-9]*Executor' "$DISP" | sort -u); do
  grep -q "import io.github.thoitiet.emuse.exec.$cls" "$DISP" \
    || fail "CommandDispatcher uses $cls but has no import for it"
  find app/src/main/java -name "$cls.kt" | grep -q . \
    || fail "class $cls referenced but $cls.kt not found"
done
echo "OK: executor imports (if no FAIL above)"

# 3) Wiring consistency: Protocol <-> ToolDefs <-> ToolGroups <-> CommandDispatcher.
python3 - <<'EOF'
import re, sys
base = "app/src/main/java/io/github/thoitiet/emuse"
proto = open(f"{base}/Protocol.kt").read()
defs = open(f"{base}/mcp/ToolDefs.kt").read()
groups = open(f"{base}/ToolGroups.kt").read()
disp = open(f"{base}/CommandDispatcher.kt").read()
fails = []

cmds = set(re.findall(r"const val (\w+) =", proto))
# every Cmds.X used in the dispatcher exists in Protocol
for used in set(re.findall(r"Cmds\.(\w+)", disp)):
    if used not in cmds:
        fails.append(f"Cmds.{used} used in CommandDispatcher but missing in Protocol.kt")
# TOOL_NAME_BY_CMD keys all exist in Protocol
for used in set(re.findall(r"Cmds\.(\w+)\s+to\s+\"", groups)):
    if used not in cmds:
        fails.append(f"Cmds.{used} in TOOL_NAME_BY_CMD but missing in Protocol.kt")
# every ToolDef name is grouped (fail-closed otherwise) and has a cmd mapping
def_names = set(re.findall(r'ToolDef\(\s*"([^"]+)"', defs))
# tools served locally (cmd = null) don't need a TOOL_NAME_BY_CMD entry
local_tools = set(re.findall(r'ToolDef\(\s*"([^"]+?)".*?,\s*null,', defs, re.S))
grouped = set(re.findall(r'"([^"]+)"\s+to\s+ToolGroup\.', groups))
mapped = set(re.findall(r'Cmds\.\w+\s+to\s+"([^"]+)"', groups))
for name in sorted(def_names):
    if name == "tool_flags":
        continue  # meta tool, exempt by design
    if name not in grouped:
        fails.append(f'tool "{name}" in ToolDefs but missing from TOOL_GROUP_BY_NAME')
    if name not in mapped and name not in local_tools:
        fails.append(f'tool "{name}" in ToolDefs but missing from TOOL_NAME_BY_CMD')
# every mapped cmd has a dispatcher case
for used in set(re.findall(r"Cmds\.(\w+)\s+to\s+\"", groups)):
    if not re.search(rf"Cmds\.{used}\s*->", disp):
        fails.append(f"Cmds.{used} mapped but has no case in CommandDispatcher.execute()")
for f in fails:
    print("FAIL:", f)
sys.exit(1 if fails else 0)
EOF
[ $? -ne 0 ] && FAIL=1 || echo "OK: tool wiring consistent"

# 4) Android API -> manifest permission audit for changed files.
#    Every Manifest.permission.X referenced in changed Kotlin must be declared
#    in AndroidManifest.xml (or documented as root-only / signature).
MANIFEST=app/src/main/AndroidManifest.xml
DECLARED=$(grep -oE 'android\.permission\.[A-Z_]+' "$MANIFEST" | sort -u)
MISSING=0
for f in $KOTLIN_FILES; do
  for p in $(grep -oE 'Manifest\.permission\.[A-Z_]+' "$f" | sed 's/Manifest\.permission\.//' | sort -u); do
    echo "$DECLARED" | grep -q "android.permission.$p" || {
      echo "FAIL: $f uses Manifest.permission.$p but it is not declared in AndroidManifest.xml"
      MISSING=1
    }
  done
done
[ $MISSING -eq 0 ] && echo "OK: manifest permissions cover all Manifest.permission.* usages" || FAIL=1

if [ $FAIL -eq 0 ]; then
  echo "verify-phase: ALL CHECKS PASSED"
else
  echo "verify-phase: FAILURES FOUND"
fi
exit $FAIL
