#!/usr/bin/env bash
# 启用仓库自带的 git 钩子（core.hooksPath 是本地配置，不会随克隆自动生效）。
# 克隆后跑一次：bash scripts/setup-git-hooks.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .githooks/pre-push ]]; then
  echo "找不到 .githooks/pre-push" >&2
  exit 2
fi

chmod +x .githooks/pre-push scripts/ci-guard.sh
git config core.hooksPath .githooks

echo "✔ 已启用 git 钩子：core.hooksPath=$(git config --get core.hooksPath)"
echo "  生效的钩子："
for h in .githooks/*; do
  [[ -f "$h" ]] && echo "    $(basename "$h")"
done
echo "  验证：在 CI 跑着的时候 git push 会被拦下（绕过用 SKIP_CI_GUARD=1）"
