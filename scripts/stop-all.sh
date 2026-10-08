#!/usr/bin/env bash
#
# 停掉 run-all.sh 起的所有服务。按 PID 文件停，再从端口兜底扫一遍，
# 避免「PID 文件丢了 → 进程变孤儿 → 端口被占」。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"

SERVICES=(api-gateway order-service inventory-service eureka-server config-server)

# 同 run-all.sh：bash 3.2 不支持关联数组，端口映射用 case 实现
port_of() {
  case "$1" in
    api-gateway) echo 8080 ;;
    order-service) echo 8081 ;;
    inventory-service) echo 8082 ;;
    eureka-server) echo 8761 ;;
    config-server) echo 8888 ;;
    *) echo ""; return 1 ;;
  esac
}

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

# ---- 可移植性：Linux CI runner 上通常没有 lsof ----
have_lsof() { command -v lsof >/dev/null 2>&1; }

still_up() { # $1=port —— 没有 lsof 时退回 actuator 探活（语义即「还在服务」）
  if have_lsof; then
    lsof -ti tcp:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    curl -sf -o /dev/null --max-time 2 "http://localhost:$1/actuator/health"
  fi
}

kill_stragglers() { # $1=name $2=port
  if have_lsof; then
    local pids
    pids="$(lsof -ti tcp:"$2" -sTCP:LISTEN 2>/dev/null || true)"
    [[ -n "$pids" ]] && kill -9 $pids 2>/dev/null || true
  else
    # 没有 lsof 就拿不到端口上的 PID，按 jar 名兜底
    pkill -9 -f "$1-1.0.0.jar" 2>/dev/null || true
  fi
}

for name in "${SERVICES[@]}"; do
  pid_file="$RUN_DIR/$name.pid"
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" 2>/dev/null; then
      log "停止 $name (pid=$pid)"
      kill "$pid" 2>/dev/null
    fi
    rm -f "$pid_file"
  fi
done

# 给进程一点时间优雅退出，然后强制收尾
sleep 3
for name in "${SERVICES[@]}"; do
  port="$(port_of "$name")"
  if still_up "$port"; then
    log "端口 ${port} 仍被占用（${name}），强制结束"
    kill_stragglers "$name" "$port"
  fi
done

echo
log "剩余监听端口检查："
any=0
for name in "${SERVICES[@]}"; do
  port="$(port_of "$name")"
  if still_up "$port"; then
    printf '  \033[31m%s (: %s) 仍在监听\033[0m\n' "$name" "$port"; any=1
  else
    printf '  \033[32m%s\033[0m: 已停止\n' "$name"
  fi
done
exit $any
