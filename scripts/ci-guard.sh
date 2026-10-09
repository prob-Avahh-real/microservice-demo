#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# CI 预推送守卫：CI 正在跑的时候，别再推后续提交。
#
# 为什么需要它
#   流水线的 concurrency 组是按 ref 的：同一分支上的新推送会让上一次运行
#   被取消（cancel-in-progress）或排队。想看完整结果，就别在飞行中推第二次。
#
#   这是「两条腿」里的**本地预防**那条，平台侧的 concurrency 是**兜底**那条：
#     本地守卫  ←  拦住「我知道在跑，还要推」这种有意/疏忽的推送
#     平台 concurrency  ←  拦住 PR 上的高频推送（那种取消是应该的）
#   只有平台侧：你会在 CI 跑着时推文档提交，把上一次真正的验证运行取消掉。
#   只有本地侧：别人/别的机器（没装钩子）照样能推爆。
#
# 用法
#   作为 git pre-push 钩子的后端（由 .githooks/pre-push 调用，git 经 stdin 传 refs）；
#   也可以单独跑：
#     bash scripts/ci-guard.sh
#
# 逃生口（确实要现在推、接受上一次运行被取消/排队）：
#   SKIP_CI_GUARD=1 git push ...
#
# 设计取舍：**失败开放（fail-open）**
#   gh 缺失 / 未登录 / 断网 / 超时 → 只提示、不拦。
#   一个会因为工具故障就挡住正常推送的守卫，比没有守卫更糟。
# ─────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -n "${SKIP_CI_GUARD:-}" ]]; then
  echo "⚠ CI 守卫：SKIP_CI_GUARD 已设置，跳过检查（上一次运行可能被取消/排队）"
  exit 0
fi

# ── 只守「推分支」。推 tag 有自己的 concurrency 组，不会取消分支运行 ──
# pre-push 从 stdin 收到若干行：<local ref> <local sha> <remote ref> <remote sha>
# 三种输入要分开对待：
#   (1) 钩子调用：stdin 有 refs → 只看 refs/heads/*，一条都没有 = 只推 tag = 放行
#   (2) 手动跑且 stdin 是管道/空文件：没有 refs → 退回当前分支
#   (3) 手动跑且 stdin 是终端：退回当前分支
branches=""
saw_refs=0
if [[ ! -t 0 ]]; then
  input="$(cat 2>/dev/null || true)"
  if [[ -n "$input" ]]; then
    saw_refs=1
    branches="$(printf '%s\n' "$input" \
      | awk '$1 ~ /^refs\/heads\// { sub(/^refs\/heads\//, "", $1); print $1 }')"
  fi
fi
if [[ -z "$branches" ]]; then
  if (( saw_refs )); then
    echo "✔ CI 守卫：非分支推送（只推 tag），放行（tag 有自己的 concurrency 组）"
    exit 0
  fi
  branches="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
fi
case "$branches" in
  ""|"HEAD") echo "✔ CI 守卫：分离头指针，没有要守的分支，放行"; exit 0 ;;
esac

if ! command -v gh >/dev/null 2>&1; then
  echo "⚠ CI 守卫：没找到 gh，跳过检查（失败开放）"
  exit 0
fi

# ── 便携超时：macOS 没有 coreutils 的 timeout，用「后台跑 + 到点 kill」 ──
OUT="$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/ci-guard.$$")"
trap 'rm -f "$OUT"' EXIT

list_runs() {   # $1 = 分支；输出该分支上**未结束**的 run；超时/失败返回非 0
  local b="$1"
  # 整段放进子 shell 并关掉它的 stderr：看门狗 kill 掉 gh 时 bash 会打一句
  # "Terminated: 15"，会污染 push 输出；而守卫放行时应当安静。
  #
  # ⚠⚠ 服务端过滤两个都别用（都实测踩过，且都**不报错**）：
  #   1. `--status a --status b`：gh 的 --status 是**单值**标志，重复给只会用**最后一个**。
  #      写成 `--status in_progress --status queued --status pending --status waiting`
  #      实际等价于 `--status waiting` → 永远查不到东西 → 守卫静默放行、
  #      **看着在工作其实从不拦截**。这是最坏的失败方式：不会响的传感器。
  #   2. 所以就取最近 50 条，在**本地**按 headBranch 匹配 + status != completed
  #      判断「还没结束」（in_progress / queued / pending / waiting / requested 都算）。
  # 教训：过滤条件是**静默**生效的 —— 写完必须拿一次真正在跑的 run 试，别靠读代码。
  (
    gh run list \
        --limit 50 \
        --json databaseId,status,headBranch,displayTitle \
        -q ".[] | select(.headBranch == \"$b\") | select(.status != \"completed\") | \"\(.databaseId)\t\(.status)\t\(.displayTitle)\"" \
        >"$OUT" 2>/dev/null &
    ghpid=$!
    ( sleep "${CI_GUARD_TIMEOUT:-15}"; kill "$ghpid" 2>/dev/null ) >/dev/null 2>&1 &
    killer=$!
    wait "$ghpid" 2>/dev/null
    rc=$?
    kill "$killer" 2>/dev/null
    exit "$rc"
  ) 2>/dev/null
}

blocked=0
for b in $branches; do
  if ! list_runs "$b"; then
    echo "⚠ CI 守卫：查询 GitHub 失败（未登录 / 断网 / 超时），跳过对 $b 的检查"
    continue
  fi
  n="$(wc -l <"$OUT" | tr -d ' ')"
  if [[ "$n" == "0" || -z "$n" ]]; then
    # 放行时默认静默（钩子老规矩）；想看它在工作就开 CI_GUARD_VERBOSE=1
    [[ -n "${CI_GUARD_VERBOSE:-}" ]] && echo "✔ CI 守卫：$b 上没有在跑的运行，放行"
    continue
  fi

  blocked=1
  echo "✘ CI 守卫：$b 上还有 ${n} 个运行没结束，先别推 —— "
  while IFS=$'\t' read -r id status title; do
    [[ -z "$id" ]] && continue
    echo "    #${id}  ${status}  ${title}"
  done <"$OUT"
  echo
  echo "  现在推会让上面这些被取消或排队，之前那次运行的结果就白跑了。"
  echo "  等它跑完：   gh run watch <上面的 #id> --exit-status"
  echo "  确实要现在推：SKIP_CI_GUARD=1 git push ..."
done

exit "$blocked"
