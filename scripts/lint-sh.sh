#!/usr/bin/env bash
#
# 仓库体检（Sensor）—— 抓三类**反复踩过**的坑，不用靠记忆：
#
#   1. bash 3.2（macOS 自带）会把「紧跟变量的多字节字符」吃进变量名：
#        "...= $before（链路..."  → 变量名变成 before<乱码> → unbound variable
#      本工程已经栽过两次，所以做成自动检查，而不是写在文档里提醒自己。
#
#   2. 语法错误：bash -n
#
#   3. 受版本控制的配置里写本机绝对路径（/opt/homebrew、/Users/...）：
#      「本机干净检出」验证抓不到这类问题（同一台机器上那些路径依然存在），
#      只有换机器（CI）才暴露 —— 实测被这个坑掉过一次 CI，故做成检查。
#
#   4. CI 守卫与 concurrency 的配置完整性：
#      预推送守卫（.githooks/pre-push）存在且可执行；ci.yml 里不许出现**无条件**的
#      cancel-in-progress: true（那会取消正在跑的主分支运行）。规则写进 AGENTS.md
#      的同时必须有东西能在被「顺手简化」回去时报警。
#
#   用法：bash scripts/lint-sh.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

problems=0

# python 解释器：macOS / Ubuntu 是 python3；Windows 的 Git Bash 里通常只有 python。
# 不写死 python3 才能在 CI 的 Windows 矩阵上跑。
PY_BIN="$(command -v python3 || command -v python || true)"
if [[ -z "$PY_BIN" ]]; then
  echo "找不到 python（需要 python3 或 python）" >&2
  exit 2
fi

# Windows 的控制台默认编码是 cp1252，Python 打印中文或 ✔ 会抛 UnicodeEncodeError
# （不是「输出难看」，是直接报错退出）—— 实测在 windows-latest 矩阵上挂过一次。
# 强制 UTF-8 输出：CI 日志按 UTF-8 解码，显示正常。
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

echo "── 1. 语法检查 (bash -n) ────────────────────────────────"
while IFS= read -r f; do
  if ! bash -n "$f" 2>/tmp/lint-sh.err; then
    echo "  ✘ $f"
    sed 's/^/      /' /tmp/lint-sh.err
    problems=$((problems + 1))
  fi
done < <(find . -name '*.sh' -not -path './mobile/node_modules/*' -not -path './*/target/*' | sort)

echo "── 2. \$var 紧跟多字节字符（bash 3.2 变量名被吃掉） ──────"
"$PY_BIN" - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
# 变量引用后面直接跟着非 ASCII 字符 —— 必须写成 ${var}
bad_pattern = re.compile(r'\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7f]')
skip = ('node_modules', '/target/', '/.git/', '/build/')

found = 0
for path in sorted(root.rglob('*.sh')):
    s = str(path)
    if any(part in s for part in skip):
        continue
    for lineno, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        # 注释行里的 $var 不会被执行，跳过（否则本文件自己的说明文字会被误报）
        if line.lstrip().startswith('#'):
            continue
        for m in bad_pattern.finditer(line):
            rel = path.relative_to(root)
            print(f"  ✘ {rel}:{lineno}  ...{m.group(0)}...  → 应写成 ${{{m.group(0)[1:-1]}}}")
            found += 1

if found:
    print(f"  共 {found} 处：把 $var 改成 ${{var}}")
else:
    print("  ✔ 未发现")
sys.exit(1 if found else 0)
PY
py_status=$?
(( py_status != 0 )) && problems=$((problems + 1))

echo "── 3. 受版本控制的配置里有没有本机绝对路径 ──────────────"
"$PY_BIN" - "$ROOT" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
pattern = re.compile(r'/opt/homebrew|/Users/')

# 只扫「执行相关」的受控文件类型：
#   - 不扫 *.md：文档里写路径是说明，不是配置
#   - 不扫 *.sh：它们用 ${VAR:-默认值} 形式，可由环境覆盖（CI 已证明成立）
watch_suffixes = {'.properties', '.gradle', '.xml', '.yml', '.yaml', '.toml', '.json', '.conf'}

tracked = subprocess.run(
    ['git', '-C', str(root), 'ls-files'], capture_output=True, text=True, check=True
).stdout.split()

bad = 0
for rel in tracked:
    path = Path(rel)
    if path.suffix not in watch_suffixes:
        continue
    full = root / rel
    if not full.is_file():
        continue
    for lineno, line in enumerate(full.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        if line.lstrip().startswith('#'):
            continue
        for match in pattern.finditer(line):
            print(f"  ✘ {rel}:{lineno}  {match.group(0)}"
                  f"  → 受控文件里别写本机绝对路径，改用环境变量或相对路径")
            bad += 1

print("  ✔ 未发现" if bad == 0 else f"  共 {bad} 处")
sys.exit(1 if bad else 0)
PY
cfg_status=$?
(( cfg_status != 0 )) && problems=$((problems + 1))

echo "── 4. CI 守卫与 concurrency 配置（防「顺手简化」回去） ────"
"$PY_BIN" - "$ROOT" <<'PY'
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
bad = 0

# (a) 预推送守卫必须在，且**索引里**带可执行位（100755）。
#     ⚠ 不能用 os.stat().st_mode 判断：Windows 没有 POSIX 可执行位，
#     Python 在那边报的 st_mode 永远没有 0o111 —— 这个检查自己就变成了
#     又一个「跨平台假设」（实测：加了它的那次 CI 在 windows-latest 上红了）。
#     索引里的 mode 才是权威且跨平台的（git 靠它决定钩子能不能跑），所以问 git 要。
guard_rel = '.githooks/pre-push'
modes = subprocess.run(['git', '-C', str(root), 'ls-files', '-s', guard_rel],
                       capture_output=True, text=True).stdout.split()
if not modes:
    print(f"  ✘ {guard_rel} 没进版本库 —— CI 预推送守卫没了")
    bad += 1
elif modes[0] != '100755':
    print(f"  ✘ {guard_rel} 在索引里的权限是 {modes[0]}，应为 100755"
          f"（没有可执行位时 git 会静默忽略钩子）")
    bad += 1

wf = root / '.github' / 'workflows' / 'ci.yml'
if not wf.is_file():
    print("  ✘ 找不到 .github/workflows/ci.yml")
    bad += 1
else:
    text = wf.read_text(encoding='utf-8')

    # (b) cancel-in-progress 不能是无条件的 true：
    #     那会把正在跑的主分支运行也取消掉（本工程踩过）
    for lineno, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if s.startswith('#') or not s.startswith('cancel-in-progress:'):
            continue
        if s.split(':', 1)[1].strip() == 'true':
            print(f"  ✘ ci.yml:{lineno}  cancel-in-progress: true（无条件）"
                  f"  → 会取消正在跑的主分支运行，应写成条件式（仅 PR 取消）")
            bad += 1

    # (c) concurrency 本身要在：它是取消/排队的兜底
    if 'concurrency:' not in text:
        print("  ✘ ci.yml 里没有 concurrency：同分支连续推送会并行跑、互相抢资源")
        bad += 1

print("  ✔ 未发现" if bad == 0 else f"  共 {bad} 处")
sys.exit(1 if bad else 0)
PY
guard_status=$?
(( guard_status != 0 )) && problems=$((problems + 1))

echo
if (( problems == 0 )); then
  echo "✔ 仓库检查通过"
  exit 0
else
  echo "✘ 仓库检查发现 ${problems} 类问题"
  exit 1
fi
