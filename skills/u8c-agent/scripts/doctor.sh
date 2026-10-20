#!/bin/bash
# u8c-agent 环境自检：编译 -> 匿名账套探测 -> 可选登录 -> profile 检查
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT="$DIR/../agent"
fail=0
step() { printf '\n== %s ==\n' "$1"; }

step "1/5 UClient 缓存"
CODE_DIR="${ERP_AGENT_CODE_DIR:-}"
if [[ -z "$CODE_DIR" ]]; then
  CODE_DIR="$(for d in "$HOME"/UClient/apps/*/u8c_client_home/NCCACHE/*/CODE; do \
    [[ -d "$d/lib" ]] && echo "$(find "$d/lib" -name '*.jar' | wc -l | tr -d ' ') $d"; \
    done 2>/dev/null | sort -rn | head -1 | awk '{print $2}')"
fi
if [[ -d "$CODE_DIR/lib" ]]; then
  echo "OK  CODE_DIR=$CODE_DIR"
else
  echo "FAIL 未找到 UClient NCCACHE 缓存（先登录一次 UClient，或设 ERP_AGENT_CODE_DIR）"; fail=1
fi

JAVA="${ERP_AGENT_JAVA:-$HOME/UClient/share/java1.8.0_202-x64/bin/java}"
[[ -f "$JAVA" ]] && echo "OK  JAVA=$JAVA" || { echo "FAIL JRE8 未找到: $JAVA（设 ERP_AGENT_JAVA）"; fail=1; }

step "2/5 编译"
if [[ $fail -eq 0 ]]; then
  (cd "$AGENT" && ./compile.sh) && echo "OK 编译完成" || { echo "FAIL 编译失败"; fail=1; }
else
  echo "SKIP（前置失败）"
fi

step "3/5 匿名账套探测（需 ERP_AGENT_SERVER）"
if [[ -z "${ERP_AGENT_SERVER:-}" ]]; then
  echo "SKIP 未设 ERP_AGENT_SERVER"
elif [[ $fail -eq 0 ]]; then
  OUT="$(echo '{"action":"accounts"}' | "$AGENT/u8-agent" 2>/dev/null)"
  echo "$OUT" | grep -q '"ok":true' && echo "OK $OUT" || { echo "FAIL $OUT"; fail=1; }
else
  echo "SKIP（前置失败）"
fi

step "4/5 登录冒烟（需 ERP_AGENT_USER/PASSWORD，占 1 license）"
if [[ -z "${ERP_AGENT_USER:-}" || -z "${ERP_AGENT_PASSWORD:-}" ]]; then
  echo "SKIP 未设登录凭据（设 ERP_AGENT_USER/ERP_AGENT_PASSWORD 后重跑可做完整自检）"
elif [[ $fail -eq 0 ]]; then
  OUT="$(echo '{"action":"login"}' | "$AGENT/u8-agent" 2>/dev/null)"
  echo "$OUT" | grep -q '"ok":true' && echo "OK 登录成功" || { echo "FAIL $OUT"; fail=1; }
else
  echo "SKIP（前置失败）"
fi

step "5/5 profile"
if [[ -n "${ERP_AGENT_PROFILE:-}" ]]; then
  if [[ -f "$ERP_AGENT_PROFILE" ]]; then
    echo "OK $ERP_AGENT_PROFILE"
  else
    echo "FAIL profile 文件不存在: $ERP_AGENT_PROFILE"; fail=1
  fi
else
  echo "INFO 未设 ERP_AGENT_PROFILE（探测类功能不受影响；写入链路需配置时会在报错中提示）"
fi

echo
[[ $fail -eq 0 ]] && echo "自检通过" || echo "自检未通过（见上）"
exit $fail
