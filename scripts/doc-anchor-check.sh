#!/usr/bin/env bash
# scripts/doc-anchor-check.sh —— 活文档锚点检查器（「甲/乙」的「乙」，收口时跑一次）
#
# 【甲：靠约定】引用 #锚点 时，锚点必须已存在于目标文档 —— 落笔时人工/agent 自查，
#   不跑脚本、零维护。本脚本不管这条，它只兜底。
# 【乙：本脚本】收口时跑一次，秒级，只做两个集合差：
#   ① 悬空引用（引了但没定义）—— 真错，exit 1
#   ② 死锚点（定义了但没人引）—— **不一定错**（可能是预留），只报告，exit 0
#
# 口径（四条，每条都有实测出处；改之前先读这里）：
#   · 只认三项前缀 `db-` / `api-` / `pack-`，**不抓**裸 `#[a-z0-9-]+`：
#     裸口径会把 hex 色值 `#fff`、DOM id `$('#sw-aux')`、URL 片段 `#vue-devtools`、
#     稿控件 id `#fr-n` / `#ho-n` / `#g-desc` 全吃进来（实测 docs/ 内 10 条「悬空」
#     无一例外都是这类假阳，真锚点 0 条）。
#   · 字符集**含下划线 `_`**：否则漏掉 `{#pack-c3-block-2-user-mall_management_sql}`
#     这类真锚点（锚点名里带 `_`，实测 3 个）。
#   · 范围 = **全仓**（排除 node_modules/.git/dist/target/_work/*.png）：cm3 把代码注释里
#     指向底座的引用也换成了锚点（实测 12 个代码文件命中），**只扫 `docs/` 会漏**。
#   · 引用用 `(?<!\{)` **排除锚点定义自身**：`{#db-6-3}` 里也含 `#db-6-3`，不排除的话
#     每个锚点都自证「被引」→ **死锚点永远查不出来**（实测差 226 条）。
#
# 用法：bash scripts/doc-anchor-check.sh

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# 本脚本自身注释里也有 `{#...}` / `#...` 示例，要排除掉，否则它把自己算成文档内容
SELF=$(basename "$0")
EX=(--exclude-dir=node_modules --exclude-dir=.git --exclude-dir=dist
    --exclude-dir=target --exclude-dir=_work --exclude='*.png' --exclude="$SELF")

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# 定义：{#db-x} / {#api-x} / {#pack-x}
grep -rhoIE '\{#(db|api|pack)-[a-z0-9_-]+\}' . "${EX[@]}" 2>/dev/null \
  | tr -d '{}' | sort -u > "$tmp/anchors"
# 引用：同样的 token，但**前面不是 `{`**（排除定义自身）
grep -rhoPI '(?<!\{)#(db|api|pack)-[a-z0-9_-]+' . "${EX[@]}" 2>/dev/null \
  | sort -u > "$tmp/refs"

na=$(wc -l < "$tmp/anchors"); nr=$(wc -l < "$tmp/refs")
echo "锚点定义 $na  ·  引用（不含定义自身） $nr"
echo

dangling=$(comm -23 "$tmp/refs" "$tmp/anchors")
dead=$(comm -13 "$tmp/refs" "$tmp/anchors")

if [ -n "$dangling" ]; then
  nd=$(printf '%s\n' "$dangling" | wc -l)
  echo "❌ 悬空引用（引了但没定义）—— $nd 条"
  printf '%s\n' "$dangling" | sed 's/^/   /'
  rc=1
else
  echo "✅ 悬空引用 0 条"
  rc=0
fi
echo

ndead=$(printf '%s\n' "$dead" | grep -c . || true)
echo "ℹ️  死锚点（定义了但没人引）—— $ndead 条（**不一定错**，可能预留；多为非 c3 旧 pack 的标题锚点）"
if [ "$ndead" -gt 0 ]; then printf '%s\n' "$dead" | sed 's/^/   /'; fi

exit $rc
