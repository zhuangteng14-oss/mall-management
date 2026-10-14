// src/api/observe.js —— 可观测面板的客户端（块 7 commit 3 的 SSE + commit 4 的三个普通 POST
// + commit 8b 的清场）。
//
// 七条端点（**全在** `endpoints.js` 里，本文件一个后端路径字面量都不出现 —— 那条判据可 grep 自动化）：
//   metrics → 指标面板（KPI / 锁 / 防护 / 库存水位）          ┐
//   orders  → 状态流转页（五态计数 / 最近订单 / 本拍流转）      ├ 三条 **SSE 流**（commit 3）
//   events  → 实时日志页（8 类归一化事件流）                  ┘
//   attackScenarios / attackRun / attackRecords → 攻击模拟器（commit 4）—— 三条**普通 POST**
//   attackCleanup → 清场（commit 8b）—— 同样**普通 POST**、**无入参**，但它是**破坏性**的（后端真 DELETE）
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
// ⚠️ 攻击那三条**走的不是流**（`http.post` + 表单串，见文件尾那节）：它们的响应是**一次性的
//   JSON**，没有「拍」的概念 —— 别为了统一而把它们也塞进 `openStream`。

import { http, streamPost } from './http'
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
 * @param {Array<{key:string,weight:number}>} [p.scenarios] 勾选与权重；**不传 = 全 8 个场景**
 *        ⚠️ 权重为 0 的场景**等于没勾**（后端会剔除），别用 0 表达「跑一下试试」。
 * @returns {Promise<{success:boolean, Id:number, Name:string, TotalRequests:number,
 *          BlockedRequests:number, FailedRequests:number, DurationMs:number, Result:object}>}
 *          `Id` = 刚落库那一行的主键（后端**先跑后落**，失败会抛 `HttpError`：400 = 入参不合法，
 *          body 里是 `{success:false,msg}`，`msg` 就是「哪个参数不对」）。
 */
export function runAttack(p) {
  const o = p || {}
  const seconds = Number(o.durationSeconds) > 0 ? Number(o.durationSeconds)
    : DEFAULT_DURATION_SECONDS
  return postJson(endpoints.observe.attackRun, q({
    name: o.name,
    concurrency: o.concurrency,
    durationSeconds: o.durationSeconds,
    // ⚠️ 数组 → JSON 串（后端只认这个；传对象或裸串会 400）
    scenarios: Array.isArray(o.scenarios) ? JSON.stringify(o.scenarios) : o.scenarios,
  }), { timeout: seconds * 1000 + RUN_GRACE_MS })
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
