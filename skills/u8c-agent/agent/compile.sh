#!/bin/bash
# 用内置 JRE8 + ECJ 编译（免装 JDK）
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="${ERP_AGENT_JAVA:-$HOME/UClient/share/java1.8.0_202-x64/bin/java}"
ECJ="$DIR/tools/ecj-3.21.0.jar"

CODE_DIR="${ERP_AGENT_CODE_DIR:-}"
if [[ -z "$CODE_DIR" ]]; then
  CODE_DIR="$(for d in "$HOME"/UClient/apps/*/u8c_client_home/NCCACHE/*/CODE; do \
    [[ -d "$d/lib" ]] && echo "$(find "$d/lib" -name '*.jar' | wc -l | tr -d ' ') $d"; \
  done 2>/dev/null | sort -rn | head -1 | awk '{print $2}')"
fi
[[ -d "$CODE_DIR/lib" ]] || { echo "UClient cache not found, set ERP_AGENT_CODE_DIR"; exit 1; }
[[ -f "$JAVA" ]] || { echo "JRE8 not found at $JAVA, set ERP_AGENT_JAVA"; exit 1; }
[[ -f "$ECJ" ]] || { echo "missing $ECJ"; exit 1; }

CP=""
for j in "$DIR"/tools/gson-*.jar; do CP="$CP:$j"; done
for j in "$CODE_DIR"/lib/*.jar; do CP="$CP:$j"; done
CP="${CP#:}"

mkdir -p "$DIR/classes"
find "$DIR/src" -name '*.java' > "$DIR/.sources.list"
"$JAVA" -jar "$ECJ" -1.8 -encoding UTF-8 -nowarn -cp "$CP" -d "$DIR/classes" @"$DIR/.sources.list"
rm -f "$DIR/.sources.list"
echo "compiled -> $DIR/classes"
