// src/api/observe.js —— 可观测面板的 SSE 客户端（块 7 commit 3）。
//
// 三条端点（`POST /admin/observe/{metrics,orders,events}/stream`，**全在** `endpoints.js` 里，
// 本文件一个后端路径字面量都不出现 —— 那条判据可 grep 自动化）：
//   metrics → 指标面板（KPI / 锁 / 防护 / 库存水位）
//   orders  → 状态流转页（五态计数 / 最近订单 / 本拍流转）
//   events  → 实时日志页（8 类归一化事件流）
//
// ⚠️ **不用浏览器原生 `EventSource`**。两条独立的理由，任一条都足够：
//   ① 它**不能带自定义请求头**，而本仓的鉴权票走 `Authorization: Bearer`（默认 profile 上
//      管理员 cookie 会被后端 `AuthInterceptor` 折成未登录，见 `http.js` 的 `buildInit`）
//      ⇒ 用 `EventSource` 必然 401；
//   ② 它只支持 `GET`，而这三条端点是 `POST`（管理端读端点在本仓**全部**是 POST）。
// ⇒ 用 `fetch` + `ReadableStream` **手工解析** `text/event-stream`。传输归 `http.streamPost`，
//   本文件只做「把字节流切成帧、把帧交给调用方」。
//
// ⚠️ 帧格式只有一种（`SseStreamWriter` 只写这一种）：`data: <单行 JSON>\n\n`。
//   `event:` / `id:` / `retry:` 三个字段后端**不写** ⇒ 本文件也不认（写了反成误导）。
//   解析仍按 SSE 规范最小形态做：可有多行 `data:`（用 `\n` 连接）、容忍 `\r\n`。

import { streamPost } from './http'
import { endpoints } from './endpoints'

/** 指标面板流（1 秒 1 帧）。 */
export function openMetricsStream(handlers) {
  return openStream(endpoints.observe.metrics, handlers)
}

/** 状态流转页流（1 秒 1 帧）。 */
export function openOrderStream(handlers) {
  return openStream(endpoints.observe.orders, handlers)
}

/** 实时日志页流（1 秒 1 帧）。 */
export function openEventStream(handlers) {
  return openStream(endpoints.observe.events, handlers)
}

/**
 * 打一条 SSE 流并按帧回调。
 *
 * @param {string} path `endpoints.observe.*` 之一
 * @param {{onOpen?:Function, onFrame:Function, onEnd?:Function}} handlers
 *   `onOpen()`            连接建立、即将开始收帧（状态 2xx 且 body 可读）
 *   `onFrame(frame)`      收到**一整帧**的 JSON 对象（一帧可能含多个卡片，形状见后端产帧服务）
 *   `onEnd(errOrNull)`    流结束：`null` = 正常收尾（服务端推满后关闭 / 调用方 close()），
 *                         非 null = 真出错（网络断、非 2xx、401、帧解析失败）
 * @returns {{close:Function, done:Promise}} `close()` 停流（页面卸载必须调，否则连接一直挂在服务端）
 *
 * ⚠️ **必须 `close()`**：`StreamingResponseBody` 在服务端占**一条异步线程**，一条不断开的
 *    连接就是一条常驻线程；页面切走了还留着 = 攒线程（后端 `SseStreamWriter` 类注释记了这条代价）。
 *
 * ⚠️ **`onEnd` 与 `close()` 的分工**：`close()` 之后**不再**回调 `onEnd`（那是调用方自己发起的，
 *    再报一次「流结束了」只会让页面把正常卸载显示成错误）。
 *
 * ⚠️ **帧解析失败 = 停流并报错**，不是跳过这一帧：与后端「宁可断流也不给半死的流」同一条口径
 *    （见 `SseStreamWriter` 类注释）。半解析的帧画到页面上，比看得见的断流更坏。
 */
export function openStream(path, { onOpen, onFrame, onEnd } = {}) {
  let closed = false
  let reader = null

  const done = (async () => {
    let res
    try {
      // 空串 body：与 `adminStatsRebuild()` 的 `post(path, '')` 同一手法（本仓 POST 一律带
      // urlencoded 头）。后端这三条端点没有 `@RequestBody`，body 不参与任何判定。
      res = await streamPost(path, '')
    } catch (e) {
      // 非 2xx（含 401 —— 未登录时由 `http.js` 的鉴权出口统一处理）在这里就结束了，
      // 不会有「一条静默的死流」。
      if (!closed) onEnd && onEnd(e)
      return
    }
    if (!res.body || typeof res.body.getReader !== 'function') {
      if (!closed) onEnd && onEnd(new Error('响应没有可读的 body（该环境不支持 ReadableStream）'))
      return
    }

    reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    if (!closed) onOpen && onOpen()

    try {
      for (;;) {
        const { value, done: eof } = await reader.read()
        if (eof) {
          buffer += decoder.decode()          // 冲掉最后一个多字节字符的残尾
          break
        }
        buffer += decoder.decode(value, { stream: true })
        // ⚠️ 先剥掉所有裸 `\r` 再切帧：帧的**内容**里不可能有裸 CR/LF（后端写出前剥过一遍），
        //    故这一步只影响分隔符 ⇒ 一个 `\r\n\r\n` 也就等价于 `\n\n`，不必再写一套 CRLF 分支。
        buffer = buffer.replace(/\r/g, '')
        let cut
        while ((cut = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, cut)
          buffer = buffer.slice(cut + 2)
          const frame = parseBlock(block)
          if (frame !== null && !closed) onFrame && onFrame(frame)
        }
      }
    } catch (e) {
      // 读失败（网络断 / 服务端异常关闭）。⚠️ 调用方自己 close() 时也会走到这里
      //（cancel 会让 await read() 抛），故用 closed 区分「谁结束的」。
      if (!closed) onEnd && onEnd(e)
      return
    }
    if (!closed) onEnd && onEnd(null)
  })()

  return {
    close() {
      closed = true
      if (reader) {
        // Promise 形态的 cancel 失败（已关闭）不必上报：调用方已经在卸载路径上了。
        Promise.resolve(reader.cancel()).catch(() => {})
      }
    },
    done,
  }
}

/**
 * 一个 SSE 块 → JSON 对象。**只认 `data:`**（其余字段后端不写）。
 *
 * 返回值 `null` = 这个块里没有 `data:`（例如心跳注释行 `: keep-alive`、或空块）——
 * 跳过它是对的；**JSON 解析失败则抛**（理由见 `openStream` 的类注释）。
 */
function parseBlock(block) {
  const data = []
  for (const line of block.split('\n')) {
    if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (data.length === 0) return null
  return JSON.parse(data.join('\n'))
}
