#!/usr/bin/env bash
# ============================================================================
# scripts/check-context-packs.sh —— C3 块级 context-pack 的「文件:行号」引用校验器
#
# 用法：
#   bash scripts/check-context-packs.sh [glob]
#     glob 省略时 = docs/agent/context-packs/c3-block-*.md（只查 C3 块级 pack）
#     例：bash scripts/check-context-packs.sh 'docs/agent/context-packs/*.md'
#
# 它做什么：
#   遍历 pack，抓出两种引用 token ——
#     ① 具名引用   `<路径>.<后缀>` 或 `<路径>.<后缀>:<起>` / `:<起>-<止>` / `:<起>-...`
#     ② 简写   `:73` / `:19-24`（无文件名）—— 归属**上文最近一个「自带行号」的具名引用**
#                  （`File.java:42-154`）的路径，**可跨行沿用**。
#                  ⚠️ 只提文件名、不带行号的裸引用（`db-schema.md`）**不成为归属目标** ——
#                  否则 `S:173` / `G:347` 这类旧字母锚点、`IP:port` 会被错算到它头上，产生假 FAIL。
#   然后逐条查两件事：
#     · 文件找得到吗？（解析规则见下）
#     · 起始行在文件行数以内、且**那一行不是空的**吗？
#   最后打印报告。**只报告，不改任何文件。**
#
# 【活文档禁用行号】（2026-10-11 加，见 agent.md「文档三层治理」）——
#   引用**活文档**（`db-schema.md` / `api-contract.md` / `context-packs/*.md`）时必须写
#   `文件.md#锚点`，**不许**写 `文件.md:行号`（行号会随文档增删集体漂移，锚点不会）。
#   本脚本对「目标解析到活文档、却带了 `:行号`」的 token 直接判 **FAIL**。账本 / 代码 / 稿
#   不受此限（它们的行号本就保留）。
#
# 解析规则（路径怎么写都行，但要能唯一落到一个文件）：
#   1. `<repo>/<路径>` 直接命中 ⇒ 用它；
#   2. 以 `.../` 开头（省略前导目录）⇒ 在仓库里找 `*/<剩余>`，**必须唯一命中**；
#   3. 否则在仓库里找 `*/<路径>`，**必须唯一命中**；
#   4. 唯一 → 取该文件；0 个 ⇒ 找不到（该行若标了「待建 / 新建 / 不属本块」判 INFO，否则 WARN）；
#      多个 ⇒ FAIL 有歧义。排除 .git / node_modules / target / dist。
#
# ⚠️ 写 pack 时别把「路径 + 后缀」当**代码片段**抄进来 —— 本脚本会把它当引用 token，
#    而且它会**顶替掉**后面同行简写（`:433`）的归属目标。要举例就写文件名不带路径（见 pack 里的写法）。
#
# 退出码：有 FAIL ⇒ 1；只有 INFO / WARN 或全绿 ⇒ 0。
#
# ⚠️ 它**查不到**什么（别把 OK 当「都对」）：
#   · 行号**指对了没有** —— 只验「这一行非空」，指到隔壁一行它照样报 OK；
#   · pack 里**写漏**的引用（没写成 token 的路径，它看不见）；
#   · 语义正确性；也**不会**替你读 pack —— agent 自己 Read。
# ============================================================================
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GLOB="${1:-docs/agent/context-packs/c3-block-*.md}"

# 「具名引用」或「同行简写」的 token。后缀白名单刻意写死 + 用 \b 收尾：
# 宁可漏，不可误吞 —— `goods.update` / `AuditService.record` 这类**不是文件**的点号词必须不被吞。
TOKEN_RE='[A-Za-z0-9_./-]+\.(java|js|vue|md|sql|sh|html|properties|css|xml|yml|yaml|json)(:[0-9]+(-([0-9]+|\.\.\.))?)?|:[0-9]+(-[0-9]+)?'

fail=0
warn=0
info=0
ok=0
packs=0

report() { printf '  %-6s %s\n' "$1" "$2"; }

# 在仓库里按「后缀匹配」找候选（唯一命中才算）
resolve_by_suffix() {
    find "$REPO" \
        -path '*/.git' -prune -o \
        -path '*/node_modules' -prune -o \
        -path '*/target' -prune -o \
        -path '*/dist' -prune -o \
        -type f -path "*/$1" -print 2>/dev/null | sort -u
}

# ── 目标是不是「活文档」？（见 agent.md「文档三层治理」）────────────────────
#   活文档 = docs/agent/db-schema.md / docs/agent/api-contract.md / docs/agent/context-packs/*.md
#   只有它们的引用被要求走 `#锚点`；账本 / 代码 / 稿不在此列（行号保留）。
is_live_doc() {
    case "$1" in
        "$REPO/docs/agent/db-schema.md"|\
        "$REPO/docs/agent/api-contract.md"|\
        "$REPO"/docs/agent/context-packs/*.md) return 0 ;;
        *) return 1 ;;
    esac
}

for pack in $REPO/$GLOB; do
    [ -f "$pack" ] || continue
    packs=$((packs + 1))
    printf '\n── %s\n' "${pack#$REPO/}"

    # 「简写」的归属目标：上文最近一个**自带行号**的具名引用（跨行沿用）。
    # 裸提文件名（无行号）**不**更新它 ⇒ 不会被误当归属目标（见文件头 ②）。
    lastpath=""
    lineno=0

    while IFS= read -r line; do
        lineno=$((lineno + 1))
        # shellcheck disable=SC2013
        for tok in $(grep -oE "$TOKEN_RE" <<<"$line"); do
            refpath=""; refspan=""
            case "$tok" in
                :*)                       # 简写：归属上文最近的「带行号」具名引用（跨行沿用）
                    refpath="$lastpath"; refspan="${tok#:}" ;;
                *:[0-9]*)                 # 具名引用（自带行号）—— 成为后续简写的归属目标
                    refpath="${tok%%:*}"; refspan="${tok##*:}"; lastpath="$refpath" ;;
                *)                        # 具名引用（无行号）—— **不**成为归属目标
                    refpath="$tok" ;;
            esac

            if [ -z "$refpath" ]; then
                warn=$((warn + 1))
                report "WARN" "$lineno: 简写 '$tok' 之前没有**带行号**的具名引用，无法归属"
                continue
            fi

            # ── 找文件 ────────────────────────────────────────────────
            target=""; hits=""
            case "$refpath" in
                .../*)
                    hits="$(resolve_by_suffix "${refpath#.../}")"
                    ;;
                *)
                    if [ -f "$REPO/$refpath" ]; then
                        target="$REPO/$refpath"
                    else
                        hits="$(resolve_by_suffix "$refpath")"
                    fi
                    ;;
            esac
            if [ -z "$target" ] && [ -z "$hits" ]; then
                if grep -qE '待建|新建|不属本块' <<<"$line"; then
                    info=$((info + 1))
                    report "INFO" "$lineno: '$refpath' 不存在 —— 该行标了「待建 / 新建 / 不属本块」，按预期跳过"
                else
                    warn=$((warn + 1))
                    report "WARN" "$lineno: 找不到 '$refpath'（可能是**待建**文件但没标；也可能是笔误）"
                fi
                continue
            fi
            if [ -z "$target" ]; then
                n="$(printf '%s\n' "$hits" | grep -c . || true)"
                if [ "$n" -gt 1 ]; then
                    fail=$((fail + 1))
                    report "FAIL" "$lineno: '$refpath' 有 $n 个候选，无法唯一解析"
                    continue
                fi
                target="$hits"
            fi

            # ── 没用行号 ⇒ 只验存在 ───────────────────────────────────
            if [ -z "$refspan" ]; then
                ok=$((ok + 1))
                continue
            fi

            # ── 活文档禁用行号（见 agent.md「文档三层治理」）────────────
            #   指向活文档却带了 `:行号` ⇒ FAIL（应写 `文件.md#锚点`）。
            #   （简写只归属「自带行号的具名引用」⇒ 走到这里的目标必是真引用；裸提
            #      活文档名不带行号的情形不会被简写误带入，判 FAIL 无假阳性。）
            if is_live_doc "$target"; then
                fail=$((fail + 1))
                report "FAIL" "$lineno: '${refpath}:${refspan}' 指向**活文档**却用了 :行号 —— 必须改写 '${refpath}#锚点'（见 agent.md「文档三层治理」）"
                continue
            fi

            # ── 验行号 ────────────────────────────────────────────────
            start="${refspan%%-*}"
            total="$(awk 'END{print NR}' "$target")"
            if [ "$start" -lt 1 ] || [ "$start" -gt "$total" ]; then
                fail=$((fail + 1))
                report "FAIL" "$lineno: ${refpath}:${start} 起始行越界（该文件共 $total 行）"
                continue
            fi
            if [ -z "$(sed -n "${start}p" "$target" | tr -d '[:space:]')" ]; then
                fail=$((fail + 1))
                report "FAIL" "$lineno: ${refpath}:${start} 起始行是**空行**"
                continue
            fi
            # 止行只验存在（区间中间的空行是正常的）；`-...` 这类开放区间按 start 处理
            end="${refspan##*-}"
            case "$end" in ''|*[!0-9]*) end="$start" ;; esac
            if [ "$end" -lt "$start" ] || [ "$end" -gt "$total" ]; then
                fail=$((fail + 1))
                report "FAIL" "$lineno: ${refpath}:${refspan} 止行不合法（该文件共 $total 行）"
                continue
            fi
            ok=$((ok + 1))
        done
    done < "$pack"
done

printf '\n════════ %d 个 pack · 引用 OK %d · INFO %d · WARN %d · FAIL %d ════════\n' "$packs" "$ok" "$info" "$warn" "$fail"
if [ "$fail" -gt 0 ]; then
    echo "⇒ 有 FAIL：上面每一行都要修（改 pack 的行号 / 路径，或改被引文件 —— 改后者要同步重指）。"
    echo '   指向「活文档」的 :行号 一律改成 #锚点（见 agent.md「文档三层治理」）。'
    exit 1
fi
if [ "$warn" -gt 0 ]; then
    echo "⇒ 有 WARN：两类 ——"
    echo "   ① **没标「待建」却又找不到**的路径：要么补标，要么是笔误。"
    echo "   ② **简写 :N 上文没有「带行号」的具名引用可归属**：先把文件名连行号写全一次，或本行写全。"
fi
if [ "$info" -gt 0 ]; then
    echo "（INFO 是**预期内**的：那些行本就写着「待建 / 新建」，见 c3-blocks-index.md §一。）"
fi
echo "⚠️ 本脚本只验「文件找得到 + 起始行非空 + 活文档不用行号」；**行号指对了没有、锚点指向对不对、pack 写漏了没有，它查不到**。"
exit 0
