#!/usr/bin/env bash
#
# shell 脚本体检（Sensor）—— 抓两类**反复踩过**的坑，不用靠记忆：
#
#   1. bash 3.2（macOS 自带）会把「紧跟变量的多字节字符」吃进变量名：
#        "...= $before（链路..."  → 变量名变成 before<乱码> → unbound variable
#      本工程已经栽过两次，所以做成自动检查，而不是写在文档里提醒自己。
#
#   2. 语法错误：bash -n
#
#   用法：bash scripts/lint-sh.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

problems=0

echo "── 1. 语法检查 (bash -n) ────────────────────────────────"
while IFS= read -r f; do
  if ! bash -n "$f" 2>/tmp/lint-sh.err; then
    echo "  ✘ $f"
    sed 's/^/      /' /tmp/lint-sh.err
    problems=$((problems + 1))
  fi
done < <(find . -name '*.sh' -not -path './mobile/node_modules/*' -not -path './*/target/*' | sort)

echo "── 2. \$var 紧跟多字节字符（bash 3.2 变量名被吃掉） ──────"
python3 - "$ROOT" <<'PY'
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

echo
if (( problems == 0 )); then
  echo "✔ 脚本检查通过"
  exit 0
else
  echo "✘ 脚本检查发现 ${problems} 类问题"
  exit 1
fi
