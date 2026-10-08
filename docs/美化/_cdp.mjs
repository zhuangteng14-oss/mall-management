// _cdp.mjs —— 探针底座（2026-10-08）。headless Edge + CDP over WebSocket。
// ⚠️ 不入库（同本目录既有的 shotNN.mjs / probe-*.mjs 口径）。
// 用法：import { open, sleep } from './_cdp.mjs'
//   const b = await open({ w: 1440, h: 900, headed: false })
//   await b.goto('http://localhost:8080/#/customer')
//   await b.shot('out.png')
//   await b.close()
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const MALL = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function get(url) {
  return new Promise((res, rej) => {
    http.get(url, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => res(d)) }).on('error', rej)
  })
}

/** 打开一个受控浏览器。opts: { w, h, headed, mock } —— mock = (url, method, postData) => {status, body} | null */
export async function open({ w = 1440, h = 900, headed = false, mock = null } = {}) {
  const port = 9222 + Math.floor(Math.random() * 400)
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-cdp-'))
  const args = [
    `--remote-debugging-port=${port}`, `--user-data-dir=${dir}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    `--window-size=${w},${h}`
  ]
  if (!headed) args.push('--headless=new', '--disable-gpu')
  args.push('about:blank')
  const proc = spawn(EDGE, args, { stdio: 'ignore' })

  let list = null
  for (let i = 0; i < 100; i++) {
    try { list = JSON.parse(await get(`http://127.0.0.1:${port}/json/list`)) }
    catch (e) { list = null }
    if (list && list.some((t) => t.type === 'page')) break
    await sleep(100)
  }
  const target = list.find((t) => t.type === 'page')
  const ws = new WebSocket(target.webSocketDebuggerUrl, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 })
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })

  let id = 0
  const pend = new Map()
  const listeners = []
  ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.id && pend.has(m.id)) {
      const { res, rej } = pend.get(m.id); pend.delete(m.id)
      m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result)
    } else if (m.method) {
      for (const fn of listeners) fn(m)
    }
  })
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id; pend.set(i, { res, rej })
    ws.send(JSON.stringify({ id: i, method, params }))
  })

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })

  // ── 后端 mock（默认全关；给 mock 回调即启用）──
  if (mock) {
    await send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
    listeners.push(async (m) => {
      if (m.method !== 'Fetch.requestPaused') return
      const { requestId, request } = m.params
      try {
        const r = mock(request.url, request.method, request.postData, request.headers) || { status: 200, body: '{}' }
        const body = Buffer.from(r.body ?? '').toString('base64')
        await send('Fetch.fulfillRequest', {
          requestId, responseCode: r.status || 200,
          responseHeaders: [
            { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
            { name: 'Access-Control-Allow-Credentials', value: 'true' },
            // ⚠️ 不能写 `*`：带凭据（`credentials:'include'`）的请求里，`Allow-Headers: *` **不匹配**具名头
            //    （fetch 规范：credentials mode = include 时 `*` 只当字面量）⇒ 带 `Authorization` 的
            //    POST 预检必失败（前端报「连不上服务器」）。2026-10-08 实测踩到。
            { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
            { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
            { name: 'Content-Type', value: r.ctype || 'application/json' }
          ],
          body
        })
      } catch (e) { /* 页面可能在导航中 */ }
    })
  }

  const b = {
    ws, send, port, proc,
    async goto(url, waitMs = 1500) {
      await send('Page.navigate', { url })
      await sleep(waitMs)
    },
    async evalJS(expr) {
      const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
      if (r.exceptionDetails) throw new Error('eval 失败：' + JSON.stringify(r.exceptionDetails.exception))
      return r.result.value
    },
    async shot(file) {
      const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
      fs.writeFileSync(file, Buffer.from(r.data, 'base64'))
      return file
    },
    /** 鼠标移动（hover）。 */
    async moveTo(x, y) {
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 })
      await sleep(300)
    },
    async shotFull(file) {
      const m = await send('Page.getLayoutMetrics')
      const cs = m.cssContentSize || m.contentSize
      await send('Emulation.setDeviceMetricsOverride', { width: w, height: Math.ceil(cs.height), deviceScaleFactor: 1, mobile: false })
      await sleep(400)
      const r = await send('Page.captureScreenshot', { format: 'png' })
      fs.writeFileSync(file, Buffer.from(r.data, 'base64'))
      await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })
      return file
    },
    async close() {
      try { ws.close() } catch (e) {}
      try { proc.kill() } catch (e) {}
      await sleep(200)
      try { fs.rmSync(dir, { recursive: true, force: true }) } catch (e) {}
    }
  }
  return b
}

/** 报告小工具：断言表。 */
export function report(name) {
  const rows = []
  return {
    ok(label, cond, got) { rows.push([cond ? 'OK  ' : 'FAIL', label, got === undefined ? '' : JSON.stringify(got)]); },
    dump() { for (const [s, l, g] of rows) console.log(`${s} ${l}${g ? '  → ' + g : ''}`); const bad = rows.filter((r) => r[0] === 'FAIL').length; console.log(`\n${name}: ${rows.length} 项 / ${bad} 失败`); return bad }
  }
}
