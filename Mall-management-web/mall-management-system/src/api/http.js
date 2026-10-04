// src/api/http.js —— 前端 API 层「传输层」唯一出口
//
// 职责边界（本笔只做一件事）：
//   ① 拼装请求：base URL 前缀 / Content-Type / body 形态
//
// ⚠️ 本层**不判业务成功**。`body.success` 的语义各页不同（有的弹 msg、有的静默、
//    有的走另一分支），统一判 = 一次性改 34 处行为 ⇒ 留在各 view 自己判。
//    本层返回的是**未解析的 Response**，调用方照旧 `await res.json()`。
//
// 基址来源：`.env.development` / `.env.production` 的 `VUE_APP_API_BASE`（Vue CLI 构建期内联）。
const API_BASE = process.env.VUE_APP_API_BASE || ''

// 静态资源（图片）前缀。⚠️ 与 API_BASE **分开命名** —— 反代可能给静态资源不同前缀。
const ASSET_BASE = process.env.VUE_APP_ASSET_BASE || ''

/** 相对路径 → 绝对 URL。已是绝对 URL 则原样返回（供「URL 变量」类调用点使用）。 */
export function urlOf(path) {
  return /^https?:\/\//.test(path) ? path : API_BASE + path
}

/** 静态资源路径 → 绝对 URL。 */
export function assetOf(path) {
  return ASSET_BASE + path
}

/**
 * 组装 fetch init。三种 body 形态与改前**逐字一致**：
 *   FormData                 → 不设 Content-Type（浏览器自带 boundary）
 *   URLSearchParams / string → application/x-www-form-urlencoded
 *   POST 无 body             → 仍带 application/x-www-form-urlencoded（改前 2 处即如此）
 *   GET  无 body             → **不带任何头**（改前唯一那处 GET 是裸 `fetch(url)`）
 *
 * ⚠️ 头之所以要看 method：改前 41 处 POST **全部**带 urlencoded 头（含 2 处无 body 的
 *    `goodsSelectHome` / `goodstype`），而唯一的 GET（`GoodsAdd.loadGoodsType`）是裸
 *    `fetch(url)`、一个头都没有。「无 body 也带头」这条规则不能直接套到 GET 上 ——
 *    本笔自查时旧写法就给那个 GET 多送了一个 Content-Type（后端 `@GetMapping` 无
 *    `@RequestBody`，实际无影响，但违反「搬家 = 逐字不变」）。
 *
 * ⚠️ 此处待鉴权笔填充（见方案 §4 ①）：
 *    BD-03 选 A（拦截器 + Redis Session）⇒ 加 `credentials: 'include'`
 *      （后端 `ImgConfig` 的 CorsFilter 已是 addAllowedOriginPattern('*') + allowCredentials(true)，现成）
 *    BD-03 选 B（JWT）⇒ 加 `Authorization: \`Bearer ${getToken()}\``
 */
function buildInit(method, body) {
  const init = { method }
  if (body instanceof FormData) {
    init.body = body
  } else {
    const hasBody = body !== undefined && body !== null
    if (method === 'POST' || hasBody) {
      init.headers = { 'Content-Type': 'application/x-www-form-urlencoded' }
    }
    if (hasBody) init.body = body
  }
  return init
}

async function request(path, { method = 'GET', body } = {}) {
  return fetch(urlOf(path), buildInit(method, body))
}

export function get(path, opts) {
  return request(path, { ...opts, method: 'GET' })
}

export function post(path, body, opts) {
  return request(path, { ...opts, method: 'POST', body })
}

/**
 * 命名空间出口 —— view 统一 `import { http } from '../api/http'` 后写 `http.post(...)`。
 * ⚠️ 漏掉这一行 = 全站每个请求都变成 `undefined.post`，而 webpack 只给 **warning**、
 *    构建**退出码仍是 0** ⇒ 本笔实际踩到，靠 build 日志里的
 *    `export 'http' was not found` 才逮住。改动本文件时务必保留。
 */
export const http = { get, post }

/**
 * view 的统一 catch 出口（P2.3）。
 * 改前 14 个 view 共 41 个 catch 块，各自 `console.error/console.log/alert`，文案散且不统一。
 *
 * ⚠️ 只接「原先就只写日志」的那类 catch —— 实测 **22 处**。改前就在**弹提示**或
 *    **改组件状态**的另 19 处一律没动 —— 那些是业务分支，不是日志出口（换掉 = 改行为）。
 */
export function handleApiError(e, msg) {
  const label = msg || '请求失败'
  console.error(label, e)
}
