#!/usr/bin/env bash
# 用 dexsql 核对当前 APK 的 hook 锚点是否仍然唯一命中。
#
# dexsql 只有桌面 CLI/MCP 和 WASM，没有可在 Android 进程内加载的运行库，
# 所以它放在构建/分析期使用；模块运行期仍用 DexKit 做结构化解析。
#
# 用法：
#   tools/check-hook-anchors.sh [APK]
#   DEXSQL=/path/to/dexsql tools/check-hook-anchors.sh source/豆包输入法_1.5.6.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/source/豆包输入法_1.5.6.apk}"
DEXSQL="${DEXSQL:-dexsql}"
SQL_FILE="$ROOT/tools/hook-anchors.sql"

if ! command -v "$DEXSQL" >/dev/null 2>&1; then
    echo "找不到 dexsql：$DEXSQL" >&2
    echo "从 https://github.com/LSPosed/dexsql-release/releases 下载对应平台的可执行文件，" >&2
    echo "然后 DEXSQL=/path/to/dexsql $0 $APK" >&2
    exit 2
fi

if [[ ! -f "$APK" ]]; then
    echo "APK 不存在：$APK" >&2
    exit 2
fi

# dexsql 的 --sql 不接受以 -- 开头的参数，先去掉注释行。
SQL="$(grep -v '^--' "$SQL_FILE")"
OUT="$("$DEXSQL" query --input "$APK" --sql "$SQL")"
printf '%s\n' "$OUT"

if grep -q '<MISSING>' <<<"$OUT"; then
    echo >&2
    echo "有锚点失配（<MISSING>）：请更新 HookPoints.kt 的 matcher，或确认 APK 版本。" >&2
    exit 1
fi

echo >&2
echo "全部锚点已命中。" >&2
