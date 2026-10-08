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
  remaining="$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$remaining" ]]; then
    log "端口 $port 仍被占用 (pid=$remaining)，强制结束"
    kill -9 $remaining 2>/dev/null || true
  fi
done

echo
log "剩余监听端口检查："
any=0
for name in "${SERVICES[@]}"; do
  port="$(port_of "$name")"
  if lsof -ti tcp:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    printf '  \033[31m%s (: %s) 仍在监听\033[0m\n' "$name" "$port"; any=1
  else
    printf '  \033[32m%s\033[0m: 已停止\n' "$name"
  fi
done
exit $any
