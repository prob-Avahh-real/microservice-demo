#!/usr/bin/env python3
"""从标准输入的 JSON 里按点号路径取值，供 e2e.sh 断言用。

    echo "$json" | python3 scripts/jsonq.py data.status

取值失败时以非 0 退出（并打印原因到 stderr），这样 bash 里 `set -e` 或显式判断都能抓到。
布尔值打印为 true/false，null 打印为 null，对象/数组打印为紧凑 JSON。
"""
import json
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: jsonq.py <dotted.path>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    raw = sys.stdin.read()
    try:
        current = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"input is not valid JSON: {exc}", file=sys.stderr)
        return 3

    for part in [p for p in path.split(".") if p != ""]:
        try:
            if isinstance(current, list):
                current = current[int(part)]
            else:
                current = current[part]
        except (KeyError, IndexError, ValueError, TypeError) as exc:
            print(f"path '{path}' not found (at '{part}'): {exc}", file=sys.stderr)
            return 4

    if isinstance(current, bool):
        print("true" if current else "false")
    elif current is None:
        print("null")
    elif isinstance(current, (dict, list)):
        print(json.dumps(current, ensure_ascii=False, separators=(",", ":")))
    else:
        print(current)
    return 0


if __name__ == "__main__":
    sys.exit(main())
