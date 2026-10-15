#!/usr/bin/env node
/**
 * 探针 · D 段 commit 2 —— **三方防护 key 表**是不是逐字同序。
 *
 * 【为什么要有它：Java 侧有一条测试管不到的那一份】
 *   后端演练入参白名单   AttackEngine.PROTECTION_KEYS   ← 表外的 key 一律 400
 *   后端门的声明序       ProtectionGate.KEYS            ← 面板第 N 枚 = 第 N 个 key
 *   前端面板的声明序     observe-labels.js PROTECTIONS  ← **这一份 Java 测不到**
 * 前两份由 `ProtectionKeysAlignmentTest` 钉住（同序 + 取值）。第三份跨语言，
 * JUnit 读不到 ⇒ 只能由本探针 grep 三处比对。**这是本探针存在的唯一理由。**
 *
 * 【判据】
 *   三份**逐字同序**才算过。任一不同 ⇒ 退出码 1。
 *   ⚠️ 只比「同集合」是不够的：顺序被人读（结果页勾选清单、面板第 N 枚），
 *      两份表顺序一岔**不会报错**，只会把「第 2 个开关」指到另一个防护上。
 *
 * 【跑法】
 *   node scripts/probes/probe-d2-protection-keys-alignment.mjs
 *   退出码 0 = 三份一致；1 = 不一致（打印逐份对照）
 *
 * ⚠️ 本探针**只读**三个源文件，不连后端、不连库（不需要起服务）。
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..', '..')

const SOURCES = [
  {
    who: '后端 · 演练入参白名单',
    file: 'Mall-management-server/src/main/java/com/minke/mallmanagement/observe/service/AttackEngine.java',
    re: /PROTECTION_KEYS\s*=\s*List\.of\(([^)]*)\)/,
  },
  {
    who: '后端 · 门的声明序',
    file: 'Mall-management-server/src/main/java/com/minke/mallmanagement/protect/ProtectionGate.java',
    re: /List<String>\s+KEYS\s*=\s*List\.of\(([^)]*)\)/,
  },
  {
    who: '前端 · 面板的声明序',
    file: 'Mall-management-web/mall-management-system/src/config/labels/observe-labels.js',
    re: /export\s+const\s+PROTECTIONS\s*=\s*\[([^\]]*)\]/,
  },
]

/** 从一段 `"a","b"` 里抠出 key；两种引号、可换行。 */
function parseKeys(raw) {
  return [...raw.matchAll(/["'`]([A-Za-z0-9_]+)["'`]/g)].map((m) => m[1])
}

const found = SOURCES.map((s) => {
  const path = resolve(root, s.file)
  const text = readFileSync(path, 'utf8')
  const m = text.match(s.re)
  if (!m) {
    return { ...s, keys: null, line: null }
  }
  // 行号：匹配起点之前有几个换行
  const line = text.slice(0, m.index).split('\n').length
  return { ...s, keys: parseKeys(m[1]), line }
})

let ok = true
console.log('三方防护 key 对照（D 段 · commit 2）')
console.log('='.repeat(72))
for (const f of found) {
  if (!f.keys) {
    ok = false
    console.log(`✗ ${f.who}`)
    console.log(`    ${f.file}`)
    console.log('    **没有匹配到声明** —— 表被改名/改写了？探针的前提先坏了，不是「一致」')
    continue
  }
  console.log(`· ${f.who}`)
  console.log(`    ${f.file}:${f.line}`)
  console.log(`    [${f.keys.join(', ')}]`)
}
console.log('='.repeat(72))

const base = found[0].keys
if (base) {
  for (const f of found.slice(1)) {
    if (!f.keys) continue
    const same = f.keys.length === base.length && f.keys.every((k, i) => k === base[i])
    if (same) {
      console.log(`✓ ${f.who}：与「${found[0].who}」逐字同序`)
    } else {
      ok = false
      console.log(`✗ ${f.who}：与「${found[0].who}」**不一致**`)
      console.log(`    基准 [${base.join(', ')}]`)
      console.log(`    实测 [${f.keys.join(', ')}]`)
      const onlyBase = base.filter((k) => !f.keys.includes(k))
      const onlyThis = f.keys.filter((k) => !base.includes(k))
      if (onlyBase.length) console.log(`    只在基准里有：${onlyBase.join(', ')}`)
      if (onlyThis.length) console.log(`    只在这一份里有：${onlyThis.join(', ')}`)
      if (!onlyBase.length && !onlyThis.length) console.log('    集合相同、**顺序不同** —— 这正是「不报错但指错开关」的那种坏')
    }
  }
}

console.log('='.repeat(72))
console.log(ok ? '结论：三份逐字同序 ✓' : '结论：不一致 ✗（退出码 1）')
process.exit(ok ? 0 : 1)
