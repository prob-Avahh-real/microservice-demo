#!/usr/bin/env bash
#
# 端到端验收 —— 这是本工程的「Done」判据。
#
# 真实起 5 个进程，真实跨服务调用，逐条断言。任何一条不过就退出码非 0。
# 全部断言都对着**行为**（HTTP 返回 + 库存真的变了），不靠读日志猜。
#
#   bash scripts/e2e.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY="http://localhost:8080"
ORDER_DIRECT="http://localhost:8081"
INV_DIRECT="http://localhost:8082"
EUREKA="http://localhost:8761"

PASS=0
FAIL=0
TOTAL=8

pass() { PASS=$((PASS + 1)); printf '  \033[32m✔ PASS\033[0m %s\n' "$1"; }
fail() {
  FAIL=$((FAIL + 1))
  printf '  \033[31m✘ FAIL\033[0m %s\n' "$1"
  [[ -n "${2:-}" ]] && printf '         └─ %s\n' "$2"
  return 0
}
banner() { printf '\n\033[36m── %s\033[0m\n' "$1"; }

val() { printf '%s' "$1" | python3 "$ROOT/scripts/jsonq.py" "$2" 2>/dev/null; }

post_order() {
  curl -s --max-time 20 -X POST -H 'Content-Type: application/json' \
    -d "{\"sku\":\"$1\",\"quantity\":$2}" "$GATEWAY/api/orders"
}
get_inventory() { curl -s --max-time 10 "$GATEWAY/api/inventory/$1"; }
set_chaos() { curl -s --max-time 10 -X POST "$GATEWAY/api/inventory/chaos?enabled=$1" >/dev/null; }

echo "================================================================"
echo " 微服务端到端验收  (gateway=$GATEWAY)"
echo "================================================================"

banner "0. 拉起全套服务（幂等，已在跑就跳过）"
if ! bash "$ROOT/scripts/run-all.sh"; then
  echo
  echo "服务没能全部就绪，验收终止。"
  exit 1
fi

# ---------------------------------------------------------------- 1
banner "1. 网关 → 注册中心 → 库存服务：查库存"
before_json="$(get_inventory MONITOR)"
before="$(val "$before_json" data.available)"
if [[ "$before" =~ ^[0-9]+$ ]]; then
  pass "经网关查到 MONITOR 可用量 = ${before}（链路 网关→Eureka→inventory 通）"
else
  fail "经网关查库存没拿到可用量" "响应：$before_json"
  echo; echo "前序链路不通，后续断言无意义，终止。"; exit 1
fi

# ---------------------------------------------------------------- 2
banner "2. 经网关下单 → CREATED"
order_json="$(post_order MONITOR 3)"
status="$(val "$order_json" data.status)"
remaining="$(val "$order_json" data.remaining)"
expected_remaining=$((before - 3))
if [[ "$status" == "CREATED" && "$remaining" == "$expected_remaining" ]]; then
  pass "下单成功 status=CREATED，remaining=${remaining}（= ${before} - 3）"
else
  fail "下单没有 CREATED 或剩余量不对" "期望 status=CREATED remaining=${expected_remaining}；实际 $order_json"
fi
order_no="$(val "$order_json" data.orderNo)"

# ---------------------------------------------------------------- 3
banner "3. 再查库存 → 余额真的减少了"
after="$(val "$(get_inventory MONITOR)" data.available)"
if [[ "$after" == "$expected_remaining" ]]; then
  pass "库存从 $before 变成 $after —— 是真的扣了，不是「假成功」"
else
  fail "库存没有真的减少，怀疑下单是假成功" "期望 ${expected_remaining}，实际 $after"
fi

# ---------------------------------------------------------------- 4
banner "4. 库存不足 → REJECTED + INSUFFICIENT_STOCK（业务拒绝，不是系统错误）"
# PEAR 只有 3 件；下单 4 件在单笔上限(20)以内，所以拦它的一定是库存逻辑
reject_json="$(post_order PEAR 4)"
reject_status="$(val "$reject_json" data.status)"
reject_reason="$(val "$reject_json" data.reason)"
if [[ "$reject_status" == "REJECTED" && "$reject_reason" == "INSUFFICIENT_STOCK" ]]; then
  pass "超量下单被正确拒绝：status=REJECTED reason=INSUFFICIENT_STOCK"
else
  fail "库存不足没被正确识别" "响应：$reject_json"
fi

# ---------------------------------------------------------------- 5
banner "5. 单笔上限来自配置中心（本地兜底是 1，配置中心是 20）"
ok15="$(val "$(post_order MONITOR 15)" data.status)"
over25_json="$(post_order MONITOR 25)"
over25="$(val "$over25_json" data.status)"

if [[ "$ok15" == "CREATED" && "$over25" == "REJECTED" ]]; then
  pass "15 件放行、25 件被拒 → 上限确实是配置中心下发的 20（不是本地兜底的 1）"
else
  fail "单笔上限没按配置中心的值生效" "15 件=${ok15}（期望 CREATED）；25 件=${over25}（期望 REJECTED）"
fi

# ---------------------------------------------------------------- 6
banner "6. 统一配置中心确实下发了配置（属性源 + 行为双重证据）"
inv_diag="$(curl -s --max-time 10 "$INV_DIRECT/diagnostics/config")"
ord_diag="$(curl -s --max-time 10 "$ORDER_DIRECT/diagnostics/config")"
inv_flag="$(val "$inv_diag" data.configServerPropertySourcePresent)"
ord_flag="$(val "$ord_diag" data.configServerPropertySourcePresent)"
inv_thr="$(val "$inv_diag" data.lowStockThreshold)"
ord_max="$(val "$ord_diag" data.maxQuantityPerOrder)"
pear_low="$(val "$(get_inventory PEAR)" data.lowStock)"

if [[ "$inv_flag" == "true" && "$ord_flag" == "true" && "$inv_thr" == "5" \
      && "$ord_max" == "20" && "$pear_low" == "true" ]]; then
  pass "两个服务的属性源里都有 configserver；阈值=${inv_thr} 上限=${ord_max}；PEAR(3件) lowStock=true 与阈值 5 一致"
else
  fail "配置中心没有真正生效" \
    "inventory{fromConfigServer=$inv_flag threshold=$inv_thr} order{fromConfigServer=$ord_flag max=$ord_max} pear.lowStock=$pear_low"
fi

# ---------------------------------------------------------------- 7
banner "7. 熔断降级真实生效（打开故障注入，看熔断器跳闸）"
set_chaos true
types_seen=""
degraded_count=0
for i in 1 2 3 4 5; do
  r="$(post_order MONITOR 1)"
  s="$(val "$r" data.status)"
  rsn="$(val "$r" data.reason)"
  et="$(val "$r" data.errorType)"
  types_seen="$types_seen $et"
  [[ "$s" == "DEGRADED" && "$rsn" == "UPSTREAM_UNAVAILABLE" ]] && degraded_count=$((degraded_count + 1))
done

if [[ "$degraded_count" == "5" ]] && [[ "$types_seen" == *"CallNotPermittedException"* ]]; then
  pass "5/5 都是 DEGRADED + UPSTREAM_UNAVAILABLE，且出现了 CallNotPermittedException（熔断器真的打开了）"
else
  fail "熔断降级没按预期生效" "degraded=$degraded_count/5；观察到的 errorType:$types_seen"
fi

# 还原：关掉注入并等熔断器转半开，让本脚本可重复运行
set_chaos false
sleep 11

# ---------------------------------------------------------------- 8
banner "8. 注册中心里三个服务都在"
# 用 JSON API 而不是 grep XML：XML 里 dataCenterInfo 也带一个 <name>MyOwn</name>
# （它表示 "my own datacenter"，不是应用名），用正则抓 <name> 会把它当成第 4 个应用。
# 这类「看起来通过、其实读数错」的断言最危险，所以改成走 JSON 只取真正的应用名。
apps_raw="$(curl -s --max-time 5 -H 'Accept: application/json' "$EUREKA/eureka/apps" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(' '.join(a['name'] for a in d.get('applications', {}).get('application', [])))
" 2>/dev/null)"
apps_upper="$(printf '%s' "$apps_raw" | tr '[:lower:]' '[:upper:]')"
count=0
for s in INVENTORY-SERVICE ORDER-SERVICE API-GATEWAY; do
  [[ "$apps_upper" == *"$s"* ]] && count=$((count + 1))
done
if [[ "$count" == "3" ]]; then
  pass "Eureka 注册表：$apps_raw"
else
  fail "注册表里缺服务（只找到 $count/3）" "注册表内容：$apps_raw"
fi

# ---------------------------------------------------------------- 汇总
echo
echo "================================================================"
if [[ "$FAIL" == "0" ]]; then
  printf ' \033[32m验收通过：%d/%d 项断言全部 PASS\033[0m\n' "$PASS" "$TOTAL"
  echo "================================================================"
  echo " 订单号样例：${order_no:-n/a}"
  echo " 服务还在运行，停止： bash scripts/stop-all.sh"
  exit 0
else
  printf ' \033[31m验收未通过：%d 项 PASS / %d 项 FAIL（共 %d 项）\033[0m\n' "$PASS" "$FAIL" "$TOTAL"
  echo "================================================================"
  echo " 日志目录： $ROOT/.run/logs"
  exit 1
fi
