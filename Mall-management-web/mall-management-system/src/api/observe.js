// src/api/observe.js —— 可观测面板的客户端（块 7 commit 3 的 SSE + commit 4 的三个普通 POST
// + commit 8b 的清场 + commit 10c 的暂停与三条流的自动重连）。
//
// 十一条端点（**全在** `endpoints.js` 里，本文件一个后端路径字面量都不出现 —— 那条判据可 grep 自动化）：
//   metrics → 指标面板（KPI / 锁 / 防护 / 库存水位）          ┐
//   orders  → 状态流转页（五态计数 / 最近订单 / 本拍流转）      ├ 三条 **SSE 流**（commit 3；
//   events  → 实时日志页（8 类归一化事件流）                  ┘ commit 10c 起**自带自动重连**）
//   attackScenarios / attackRun / attackRecords → 攻击模拟器（commit 4）—— 三条**普通 POST**
//   attackCleanup → 清场（commit 8b）—— 同样**普通 POST**、**无入参**，但它是**破坏性**的（后端真 DELETE）
//   attackStop    → 暂停一轮（commit 10c）—— 同样**普通 POST**、**无入参**，**非**破坏性（只置令旗）
//   attackStatus  → 实时读数（块 7 收尾）—— 同样**普通 POST**、**无入参**、**只读**；⚠️ 它是唯一
//                   一条**要被反复打**的（攻击页在演练期间 1 秒一发）⇒ 超时用短的那一档
//   replay        → 演练回放（块 7 收尾）—— 普通 POST、**只读**、有入参（`batchId` + `limit`）；
//                   ⚠️ 唯一一条**不属于 `AttackController`** 的（路径在 `/admin/observe` 根，
//                   后端是 `ReplayController` —— 它读的是事件台账，不是演练记录）
//   metricsHistory → 指标首屏历史（2026-10-17）—— 普通 POST、**只读**、可选 `limit`；
//                   一次回最近 60 拍已算好的帧（与流帧同形），页面挂载时先铺满再接着消费流
//
// ⚠️ **不用浏览器原生 `EventSource`**（只针对上面三条流）。两条独立的理由，任一条都足够：
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
//
// ⚠️ 攻击那五条**走的不是流**（`http.post` + 表单串，见文件尾那节）：它们的响应是**一次性的
//   JSON**，没有「拍」的概念 —— 别为了统一而把它们也塞进 `openStream`（`attackStatus` 虽然是
//   「1 秒一拍地读」，但那**不是流**：每次都是独立的一次请求-响应，没有常驻连接可断）。

import { http, streamPost } from './http'
import { endpoints } from './endpoints'

/** 指标面板流（1 秒 1 帧）。⚠️ 块 7 · commit 10c 起：**断了会自己接回来**（见 `openResilientStream`）。 */
export function openMetricsStream(handlers) {
  return openResilientStream(endpoints.observe.metrics, handlers)
}

/** 状态流转页流（1 秒 1 帧）。⚠️ 同上：断了会自己接回来。 */
export function openOrderStream(handlers) {
  return openResilientStream(endpoints.observe.orders, handlers)
}

/** 实时日志页流（1 秒 1 帧）。⚠️ 同上：断了会自己接回来。 */
export function openEventStream(handlers) {
  return openResilientStream(endpoints.observe.events, handlers)
}

// ==================== 自动重连（块 7 · commit 10c）====================
//
// ⚠️ **本层存在的全部理由：这三条流可以「正常地」断**。它们不是一次性的 JSON，而是常驻连接，
//   而常驻连接在本仓有三种**非故障**的收尾方式：
//     ① 后端重启（演示现场天天发生）；
//     ② 中间层/浏览器掐空闲连接；
//     ③ `spring.mvc.async.request-timeout` 到点（块 7 commit 10c 已把它配进配置文件，
//        缺省的 30 秒会给每条流来一次「正常收尾」）。
//   那时 `openStream` 报的是 `onEnd(null)` —— **`errMsg` 一路为空**。页面上数字不变、也不报错，
//   于是「界面一切正常，其实早就不更新了」。这就是**静默陈旧**，比看得见的报错坏得多。
//   ⇒ 本层把「流结束」一律当事件上报（`onLost`）并自动重连。
//
// ⚠️ **退避而不是立刻重连**：后端重启那几秒里，立刻重连会打出几十条必然失败的请求
//   （每条都要走一遍鉴权 + 建连接），日志会很脏、后端启动还要陪着挨打。
//
// ⚠️ **不放弃**：退避封顶（8 秒）之后**继续**每 8 秒重试。理由是演示现场：后端被我重启一次
//   之后，三块面板应当自己恢复，而不是停在「已断开」等用户点按钮。
//
// ⚠️ `close()` 必须能停掉重连：页面切走之后还在后台每 8 秒打一次请求 = 攒连接 + 攒日志
//   （`openStream` 头注释里那条「必须 close()」的代价，在重连层上又多一层）。

/**
 * 退避序列（毫秒）。走到末尾之后**固定用最后一个**（8 秒一次）。
 * ⚠️ 5 档、首档 500ms：后端重启通常 5–15 秒 ⇒ 前几档就能接上，同时不给那十几秒添几百条请求。
 */
const RETRY_BACKOFF_MS = [500, 1000, 2000, 4000, 8000]

/**
 * 打一条**会自己接回来**的 SSE 流：`openStream` 外面套指数退避。
 *
 * @param {string} path `endpoints.observe.{metrics,orders,events}` 之一
 * @param {{onOpen?:Function, onFrame:Function, onLost?:Function}} handlers
 *   `onOpen()`          **每次**连接建立（第一次 + 每次重连成功）—— 页面在这里清断线标记
 *   `onFrame(frame)`    同 `openStream`
 *   `onLost(state)`     **流结束了**：`{ err, attempt, nextRetryInMs }`
 *                       ⚠️ `err === null` 也报（服务端正常收尾）—— 这正是本层要治的那一格；
 *                       页面**不许**用 `if (err)` 当唯一判据，否则又回到静默陈旧。
 *                       `attempt` = 这是第几次重连（1 起）、`nextRetryInMs` = 多久后重连。
 * @returns {{close:Function}} `close()` 停流**并停掉重连**（页面卸载必须调）。
 *          ⚠️ 不给 `done`：重连意味着「这一次连接」会反复结束，一个 `done` 表达不了任何东西。
 */
export function openResilientStream(path, { onOpen, onFrame, onLost } = {}) {
  let closed = false
  /** 连着断了几次（连上一次就归零）；退避档位与上报的 `attempt` 都看它。 */
  let attempt = 0
  let timer = null
  let current = null

  function start() {
    current = openStream(path, {
      onOpen() {
        attempt = 0
        if (!closed) onOpen && onOpen()
      },
      onFrame(f) {
        if (!closed) onFrame && onFrame(f)
      },
      onEnd(err) {
        current = null
        // ⚠️ 自己 close() 引起的那次收尾不报：调用方已经在卸载路径上了（与 `openStream` 同口径）。
        if (closed) return
        // ⚠️ 这里**不判 err**：正常收尾与真出错走同一条路（理由见本节头注释）。
        const delay = RETRY_BACKOFF_MS[Math.min(attempt, RETRY_BACKOFF_MS.length - 1)]
        attempt += 1
        onLost && onLost({ err: err || null, attempt, nextRetryInMs: delay })
        timer = setTimeout(() => {
          timer = null
          if (!closed) start()
        }, delay)
      },
    })
  }

  start()

  return {
    close() {
      closed = true
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      if (current) {
        current.close()
        current = null
      }
    },
  }
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

// ==================== 攻击模拟器（块 7 · commit 4）====================
//
// 三条端点（`AttackController`，`@RequestMapping("/admin/observe/attack")`）——
// 该 Controller 的**第四条**（清场）在文件尾那一节，**不属于**这里：本节三条都是「读/跑」，
// 那一条是**破坏性**的，单独成节是为了不让它混在「随便点点」的三条里：
//   scenarios —— 8 个预置场景（key / 中文名 / 同时演示的防护 / 需要的人工前提 / 缺省权重）
//   run       —— 跑一轮演练（**同步跑到结束**，见下面那条 ⚠️）
//   records   —— 演练记录（时间倒序；后端已把 `Result` 长文本拆成字段）
//
// ⚠️ **这三条不是流**，故不走 `openStream`：响应是一次性的 JSON，没有「拍」。
//
// ⚠️ **`runAttack` 必须显式传长 timeout**（本模块唯一一处传 `timeout` 的调用）：
//   后端是**同步**跑完一轮才回（拿得到「跑完就能看的数字」，代价就是这几十秒的连接），
//   而 `http.post` 的默认超时是 `DEFAULT_TIMEOUT = 10000`（10 秒，见 `http.js`）——
//   不给超时的话，60 秒的演练会在第 10 秒被前端自己 abort 成 `TimeoutError`
//   （后端那轮照跑完、照落库，页面上却只有一条超时提示）。
//
// ⚠️ **`scenarios` 是 JSON 数组串**（`[{"key":"seckill","weight":80}]`），后端收的是
//   `@RequestParam` + 自己解 JSON —— 别在这里改成「发 JSON body」：`http.post` 对 POST 一律
//   钉 `application/x-www-form-urlencoded`（见 `http.js#buildInit`），JSON body 到不了后端。
//   与 `api/sensitive.js` / `api/admin.js` 的 `q()` / `post()` 是**同形复制、不是共享**（本仓
//   `api/*.js` 各模块自带这两个内联函数）。

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。
 * ⚠️ 后端六个入参全部 `required = false`，而**空串与缺省不同**：空串会被
 * `DrillRecordService.decodeScenarios` 当「没勾」处理（这个是安全的），但 `limit=''` 会走
 * `intOr` 的「空白 = 用缺省」分支 —— 两条都对，只是别指望空串能表达「0」。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/**
 * 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body（同 `api/admin.js` 的做法）。
 */
async function postJson(path, body, opts) {
  return (await http.post(path, body, opts)).json()
}

/**
 * 一轮演练的超时余量（毫秒）：给「后端收口 + 汇总 + 落库 + 网络往返」留的。
 * ⚠️ 演练里还有一段 `redisDown` 是**另算**的 5 秒（不在 `durationSeconds` 里），故余量取 60 秒。
 */
const RUN_GRACE_MS = 60000

/** 后端缺省的持续秒数（与 `AttackEngine.DEFAULT_DURATION_SECONDS` 一致，用于算超时）。 */
const DEFAULT_DURATION_SECONDS = 60

/**
 * 8 个预置场景。
 *
 * @returns {Promise<{success:boolean, list:Array<{Key:string,Name:string,Protections:string,
 *          Needs:string,DefaultWeight:number}>}>}
 *          `Protections` 是「这个场景同时演示哪几条防护」、`Needs` 是「跑它之前需要什么人工前提」
 *          —— 两列都是**中文原话**，直接展示（别在前端再翻译一遍）。
 */
export function attackScenarios() {
  // 这条例外：body 传空串（本仓既有形态，同 `adminStatsRebuild()` 的 `post(path, '')`）。
  return postJson(endpoints.observe.attackScenarios, '')
}

/**
 * 跑一轮演练（**同步，几十秒**；见文件头那条 ⚠️）。
 *
 * @param {object} [p]
 * @param {string} [p.name]            场景名（缺省「自定义」）
 * @param {number} [p.concurrency]     总并发数（缺省 100、上限 500）
 * @param {number} [p.durationSeconds] 持续秒数（缺省 60、上限 600）
 * @param {number} [p.targetQps]       目标 QPS（commit 10c 甲的**限速**档）。
 *        ⚠️ **0 = 不限速**（「拉满」档）：后端只受在途许可约束，量出来的 QPS 是这台机器＋后端的
 *        **真实上限**，不是我们设的一个数 ⇒ 不许把 0 当「没填」而省略。上限 100000。
 * @param {Array<{key:string,weight:number}>} [p.scenarios] 勾选与权重；**不传 = 全 8 个场景**
 *        ⚠️ 权重为 0 的场景**等于没勾**（后端会剔除），别用 0 表达「跑一下试试」。
 *        ⚠️ 3a.1（乙案）起两个开关**不再是本函数的入参** —— 它们是**共享配置**，读写在
 *        `GET` / `POST /admin/observe/attack/config` 那条端点上（本文件**还没有**对应的封装函数：
 *        两个页面的开关栏一起做，排在 3a.6）。后端见到这两个键会**直接 400 并指路**（不静默忽略）。
 * @returns {Promise<{success:boolean, Id:number, Name:string, TotalRequests:number,
 *          BlockedRequests:number, FailedRequests:number, ShedRequests:number,
 *          AbandonedRequests:number, DurationMs:number, Result:object}>}
 *          `Id` = 刚落库那一行的主键（后端**先跑后落**，失败会抛 `HttpError`：400 = 入参不合法，
 *          body 里是 `{success:false,msg}`，`msg` 就是「哪个参数不对」）。
 *          ⚠️ `ShedRequests`（背压弃投）/ `AbandonedRequests`（被取消）是 commit 10c 甲的两个数，
 *          与「拦截 / 失败」**不是同一档**：弃投的请求**压根没发出去**、被取消的**没等到结果**
 *          ⇒ 页面上要单列，别把它们并进「失败」。
 */
export function runAttack(p) {
  const o = p || {}
  const seconds = Number(o.durationSeconds) > 0 ? Number(o.durationSeconds)
    : DEFAULT_DURATION_SECONDS
  return postJson(endpoints.observe.attackRun, q({
    name: o.name,
    concurrency: o.concurrency,
    durationSeconds: o.durationSeconds,
    targetQps: o.targetQps,
    // ⚠️ 数组 → JSON 串（后端只认这个；传对象或裸串会 400）。
    //    ⚠️ **空数组与缺省在后端是两件事**（`[]` = 一个都没勾 ⇒ 400；缺省 = 全 8 个场景），
    //    故这里**不**把 `[]` 滤掉 —— `q()` 只滤 `''`/`null`/`undefined`，
    //    `JSON.stringify([])` 是 `'[]'`，会照原样发出去（让后端去说那句话）。
    //    ⚠️ 3a.1（乙案）：`protections` / `injections` 两行**已从这里删除** —— 它们是共享配置，
    //    不再是本端点的入参（后端见到就 400，见本函数 JSDoc）。⚠️ **别**为了兼容在调用处
    //    「顺手同步一次」那两个值：那会把日志页刚拨的开关覆盖回去，乙案就白做了。
    scenarios: Array.isArray(o.scenarios) ? JSON.stringify(o.scenarios) : o.scenarios,
  }), { timeout: seconds * 1000 + RUN_GRACE_MS })
}

/**
 * 暂停**当前这一轮**演练（commit 10c）。
 *
 * @returns {Promise<{success:boolean, stopped:boolean, summary:string}>}
 *          `stopped` = 后端是否真的够到了那一轮（`false` = 此刻没有在跑的演练 ⇒ **不是故障**，
 *          多半是那一轮已经自己跑完了）。`summary` 是后端写好的中文一句话，**直接显示**。
 *
 * ⚠️ **它不掐断 `runAttack` 那条连接**：语义是「停止派发 + 排空在途」——本轮不再发新请求，
 *    已经在途的那批跑完（`GRACE_SECONDS` 内）再收口 ⇒ 正在等 `runAttack` 的那个调用点
 *    **几秒内**就会拿到响应（`Result.Engine.Stopped = true`），**用不着**给它换一条通路或加超时。
 * ⚠️ 超时用短的那一档（`http.post` 的默认 10 秒就够）：这条请求后端**立刻**回，
 *    它不等演练跑完 —— 与 `runAttack` 恰好相反，别顺手给它也加 `RUN_GRACE_MS`。
 * ⚠️ **不写审计**（与清场不同）：一轮没跑完就没有可落的记录 ⇒ 审计在 `runAttack` 那一侧。
 */
export function stopAttack() {
  // body 传空串：本仓 POST 一律带 urlencoded 头（同 `attackScenarios()`），后端不读 body。
  return postJson(endpoints.observe.attackStop, '')
}

/**
 * 正在跑的那一轮的**实时读数**（块 7 收尾 · 辅助条的 6 个数里那两个真数）。
 *
 * @returns {Promise<{success:boolean, Running:boolean, BatchId:string, ElapsedMs:number,
 *          Sent:number, Completed:number, Blocked:number, Failed:number, Shed:number,
 *          Abandoned:number}>}
 *          ⚠️ **键是大驼峰**（与其他五条出口一致），不是 `running` / `sent`。
 *          ⚠️ `Running=false` 时其余**全为 0 而不是 `null`** —— 演练收尾那一拍必然撞上它，
 *          调用方按「0」处理即可，别把缺值当成读数丢了。
 *
 * ⚠️ **只读**：不删数据、不改库、不写审计（与 `stopAttack` 同一档）—— 但它**不是幂等写入**，
 *    是「打一次读一次」，调用方（1 秒轮询）不必关心重复调用。
 * ⚠️ **`Sent` ≠ `Completed`**：`Sent` = 已**投递**（含此刻仍在途），`Completed` = 已**收口**。
 *    辅助条上那个「已发 M 条」用 **`Sent`** —— 用 `Completed` 会少报在途那一批
 *    （压测档下在途几百条，读数会明显落后于事实）。
 * ⚠️ 这几个数是**跑的过程中**的近似快照（各项分别取，不构成原子快照），**不是最终账** ——
 *    最终数以 `attackRecords()` 里那条 `drill_record` 为准。给界面看够用，别拿它对账。
 * ⚠️ 超时用短的那一档（`http.post` 默认 10 秒）：这条**立刻回**，它不等演练跑完 ——
 *    与 `runAttack` 恰好相反。调用方要**自己停轮询**（`running` 落下 / 组件卸载）。
 */
export function attackStatus() {
  return postJson(endpoints.observe.attackStatus, '')
}

/**
 * **演练回放**：把一个批次当时记下来的事件读回来（块 7 收尾）。
 *
 * @param {string} batchId 批次 UUID（**必填**），就是 `attackRecords()` 每行里的 `BatchId`
 * @param {number} [limit] 最多几条（缺省由**后端**给 5000 —— 本层**不**重设缺省，
 *        免得两处各有一个 5000；不传就是不传，让后端那个数说了算）
 * @returns {Promise<{success:boolean, batchId:string, list:Array, total:number, limit:number}>}
 *          ⚠️ 键名**故意不统一**：`list`/`total`/`limit` 小写（照 `attackRecords` 的信封），
 *          `batchId` 驼峰（它是「值」，与每行的 `BatchId` 同一个东西，见 `ReplayService`）。
 *          ⚠️ `total` 是**整批**条数、不是 `list.length` —— 两者不等时页面上**必须**提一句
 *          「共 N 条，本次放的是前 M 条」，否则一次被 limit 截断的回放会被画成完整回放。
 *          ⚠️ 批次不存在 / 已被清理任务删掉 ⇒ `list` 空 + `total=0`，**仍是成功**（不是错误）。
 *
 * ⚠️ **它不是攻击那几条的同类**（虽然挨着写在一起）：路径在 `/admin/observe/replay`、
 *    后端是另一个控制器（`ReplayController`）—— 回放读的是**事件台账**，不是**演练记录**。
 * ⚠️ **只读**、不必二次确认；但它读到的数据**有留存期**：攻击批次 3 天、日常 30 天之后
 *    被清理任务删掉就回放不到了（用户 2026-10-15 的口径：超期回放不到 = 接受，
 *    **页面上要明说留存期**，别让人以为「回放不到」是坏了）。
 * ⚠️ 超时用短的那一档（`http.post` 默认 10 秒）：这条不像 `attackRun` 那样等演练跑完 ——
 *    它就是一次几千行的 SELECT。⚠️ 但 `list` 可能有几千条 ⇒ **别在渲染里同步遍历它**。
 */
export function replay(batchId, limit) {
  // ⚠️ 走 `q()` 拼串（不是 `URLSearchParams` 就地拼）：与同域其余几条同路，
  //    顺带把「undefined / null / 空串一律不传」这条既有口径接过来 ——
  //    `limit` 不传时后端看到的就是「没给」，它才会用自己的缺省。
  return postJson(endpoints.observe.replay, q({ batchId, limit }))
}

/**
 * 演练记录（时间倒序）。
 *
 * @param {object} [p]
 * @param {number} [p.limit]  每页条数（缺省 20、上限 200）
 * @param {number} [p.offset] 偏移（负数归 0）
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, offset:number}>}
 *          每行的 `Result` / `Scenario` 已是**对象**（后端拆过字段）；解析失败的那行
 *          `Result` 为 `null` 并额外带 `ResultRaw` + `ResultParseError`（页面应把那种行画成
 *          「记录损坏」，别画成一个没有结果的成功演练）。
 */
export function attackRecords(p) {
  const o = p || {}
  return postJson(endpoints.observe.attackRecords, q({ limit: o.limit, offset: o.offset }))
}

// ==================== 清场（块 7 · commit 8b）====================
//
// ⚠️ **本模块唯一一条破坏性调用**（后端在 `ObservedEventCleanupDao` 里真发 `DELETE`）。
//
// ⚠️ **它没有成功与否的中间态**：后端要么删干净要么抛（DAO 层异常不吞，见
//   `ObservedEventCleanupService` 类注释）⇒ 页面拿到的 `deleted` 就是**真删掉的**，
//   **别**再写「没删到也算成功」那种兜底话术。
//
// ⚠️ **`deleted` 里只含真删了行的表**（全 0 的表不出现），`total` 是各表行数之和、
//   `summary` 是一句可直接显示的中文（如「共 10 行（comment 1 / order 3 / …）」，
//   或「共 0 行（库里没有待清的攻击数据）」）—— 调用点**应回显 `summary`**，只 toast
//   一个「成功」等于把「到底删了几行」藏起来。
//
// ⚠️ 清什么由**后端**按标记判（`SIM-%` 单号前缀 **OR** `Source='attack'` 写入标记，
//   两种谓词缺一会漏 —— 见 `ObservedEventCleanupDao.deleteAttackData` 的类注释），
//   前端**不传范围、也没有范围可传**：`q()` 在这里没有用武之地。

/**
 * 清掉攻击演练污染出来的数据（**无入参、一次清干净**）。
 *
 * @returns {Promise<{success:boolean, deleted:Object<string,number>, total:number, summary:string}>}
 *          `deleted` = 「表名 → 删掉的行数」（只含非零项）、`total` = 总行数、
 *          `summary` = 中文一句话摘要（**直接显示它**）。
 *          ⚠️ 调用点**必须先二次确认**再调本函数：这是真删，没有撤销。
 */
export function cleanupAttack() {
  // body 传空串：本仓 POST 一律带 urlencoded 头（同 `attackScenarios()`），后端不读 body。
  return postJson(endpoints.observe.attackCleanup, '')
}

// ==================== 指标页首屏历史（块 7 · 2026-10-17）====================
//
// ⚠️ **为什么要它**：`/metrics/stream` 的首帧是差分预热拍（全 0），页面一进入必然从 0 起；
//   切走再回来（组件销毁）也一样。用户 2026-10-17 报的那句「指标页数据从 0 开始走」就是它。
//   ⇒ 挂载时先读最近 60 拍铺满图表，再接着消费 SSE 的实时增量。
//
// ⚠️ **它是「一次性 JSON」，不是流**（与上面三条 SSE 的区别）⇒ 走 `http.post`，
//   别套 `openStream`：这条**立刻回**，没有「拍」也没有重连语义。

/**
 * 指标页最近 N 拍（升序）。
 *
 * @param {number} [limit] 最多几拍；**不传 = 后端窗口里全部**（缺省 60）。
 *        服务刚重启时窗口还没攒满，返回的 `size` 小于 `limit` 是**正常**的。
 * @returns {Promise<{success:boolean, list:Array, size:number}>}
 *          `list` 里每一帧与 SSE 帧**逐字同形**（`seq` / `ts` / `intervalMs` / `requests` /
 *          `cache` / `gauges` / `locks` / `protections` / `stock`）⇒ 调用方直接复用 `onFrame`
 *          的处理路径即可，**不要**写第二套解析。
 */
export function metricsHistory(limit) {
  return postJson(endpoints.observe.metricsHistory, q({ limit }))
}
