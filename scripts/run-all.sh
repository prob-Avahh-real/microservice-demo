#!/usr/bin/env bash
#
# 一键起全套微服务。零外部依赖：不需要 Docker / MySQL / Redis / Nacos，
# 起的都是普通 Spring Boot 进程，数据在各自的 H2 内存库里。
#
#   顺序：config-server → eureka-server → inventory-service → order-service → api-gateway
#
# 幂等：已在运行的服务会被跳过，可以反复执行。
# 停止：bash scripts/stop-all.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$RUN_DIR/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

# ---- Java：优先用 openjdk@21（本基准的 Java 版本），否则退回 PATH 上的 java ----
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  fi
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# 5 个 JVM 一起跑，必须限堆：这台机器 8GB 内存，默认堆（1/4 RAM × 5）会直接把机器压垮
JAVA_OPTS="-Xms64m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"

SERVICES=(config-server eureka-server inventory-service order-service api-gateway)

# 端口映射用 case 而不是关联数组：这台 Mac 只有 bash 3.2（macOS 自带），
# 它**不支持** declare -A。用关联数组会得到 "declare: -A: invalid option"，
# 然后 [config-server] 被当算术下标解析，在 set -u 下报 config: unbound variable。
port_of() {
  case "$1" in
    config-server) echo 8888 ;;
    eureka-server) echo 8761 ;;
    inventory-service) echo 8082 ;;
    order-service) echo 8081 ;;
    api-gateway) echo 8080 ;;
    *) echo ""; return 1 ;;
  esac
}

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }
bad()  { printf '\033[31m  !! \033[0m %s\n' "$*"; }

is_up() { # $1=port
  curl -sf -o /dev/null --max-time 2 "http://localhost:$1/actuator/health"
}

wait_healthy() { # $1=name $2=port $3=timeout_s
  local name=$1 port=$2 timeout=${3:-90} i=0
  while (( i < timeout )); do
    if is_up "$port"; then ok "$name 已就绪 (:$port)"; return 0; fi
    if [[ -f "$RUN_DIR/$name.pid" ]] && ! kill -0 "$(cat "$RUN_DIR/$name.pid")" 2>/dev/null; then
      bad "$name 进程已退出，最后 25 行日志："
      tail -n 25 "$LOG_DIR/$name.log" 2>/dev/null
      return 1
    fi
    sleep 1; i=$((i+1))
  done
  bad "$name 在 ${timeout}s 内没就绪，见 $LOG_DIR/$name.log"
  tail -n 25 "$LOG_DIR/$name.log" 2>/dev/null
  return 1
}

start_service() { # $1=name
  local name=$1 port
  port="$(port_of "$name")"
  local jar
  jar="$(ls -1 "$ROOT/$name/target/$name-"*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)"

  if [[ -z "$jar" ]]; then
    bad "$name 没找到 jar，先跑：mvn -B clean install -DskipTests"
    return 1
  fi

  if is_up "$port"; then
    ok "$name 已经在跑 (:$port)，跳过"
    return 0
  fi

  log "启动 $name  (jar=$(basename "$jar"))"
  # shellcheck disable=SC2086
  nohup "$JAVA_BIN" $JAVA_OPTS -jar "$jar" >"$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$RUN_DIR/$name.pid"
  wait_healthy "$name" "$port" 120
}

eureka_app_count() {
  curl -s --max-time 3 http://localhost:8761/eureka/apps 2>/dev/null \
    | grep -c '<application>' || true
}

wait_eureka_apps() { # $1=期望个数 $2=超时
  local want=$1 timeout=${2:-60} i=0 got=0
  while (( i < timeout )); do
    got=$(eureka_app_count)
    if (( got >= want )); then ok "Eureka 已注册 $got 个应用"; return 0; fi
    sleep 2; i=$((i+2))
  done
  bad "Eureka 只注册了 ${got} 个应用（期望 >= ${want}）"
  curl -s --max-time 3 http://localhost:8761/eureka/apps | grep -o '<name>[^<]*</name>' | sort -u
  return 1
}

# 网关重启后要拉一次注册表才能路由，用真实业务请求来判定「真的通了」
wait_gateway_routing() { # $1=超时
  local timeout=${1:-60} i=0
  while (( i < timeout )); do
    if curl -sf -o /dev/null --max-time 3 "http://localhost:8080/api/inventory/APPLE"; then
      ok "网关已能路由到后端服务"; return 0
    fi
    sleep 2; i=$((i+2))
  done
  bad "网关起来了但路由不通（检查 Eureka 注册与 LoadBalancer）"
  tail -n 25 "$LOG_DIR/api-gateway.log" 2>/dev/null
  return 1
}

fail=0
for name in "${SERVICES[@]}"; do
  start_service "$name" || fail=1
done

if (( fail == 0 )); then
  wait_eureka_apps 3 90 || fail=1
fi
if (( fail == 0 )); then
  wait_gateway_routing 60 || fail=1
fi

echo
if (( fail == 0 )); then
  log "全套已就绪 🎉"
  echo "      网关:     http://localhost:8080          业务入口：/api/orders  /api/inventory"
  echo "      Eureka:   http://localhost:8761"
  echo "      配置中心: http://localhost:8888/inventory-service/default"
  echo "      日志:     $LOG_DIR"
  echo "      停止:     bash scripts/stop-all.sh"
  exit 0
else
  bad "有服务没起来，见上面日志"
  exit 1
fi
