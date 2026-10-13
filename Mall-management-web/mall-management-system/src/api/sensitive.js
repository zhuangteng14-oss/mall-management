// src/api/sensitive.js —— 内容域「敏感词库」API 封装（C3 块 6 整改 · commit 5；2026-10-13）。
//
// 三个端点、一个模块（后端 `SensitiveWordController`，`@RequestMapping("/sensitive")`，三条全 POST）：
//   list   —— 词表（管理端「敏感词库」tab）。
//   add    —— 加一个词，**并立刻扫一遍历史评论**（方案 §4.5「加词后扫老评论」）。
//   delete —— 删一个词。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：三条理由与 `api/comment.js` /
// `api/report.js` 逐字同款 ——
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 本仓 `http.post` 的既有形态。
//      ⚠️ **别改成发 JSON**：后端收的是 `@RequestParam`，`application/json` 的 body 它**不绑定**，
//      而请求照样 200 ⇒ 表现为「静默没加上 / 静默没删掉」；
//   ③ **空值一律不发** —— 后端入参全部 `required = false` + Service 手判。
//
// ⚠️ 与 `api/comment.js` 的 `q()` / `post()` 是**同形复制、不是共享** —— 本仓 `api/*.js`
//    各模块自带这两个内联函数，未抽公共模块。
//
// ⚠️ **三条都是管理端面** —— 按本仓一贯做法**不挂 `requireAdmin`**（见 `SensitiveWordController`
//    类注释的 ⚠️：靠 `AuthInterceptor` + `app.auth.enabled` 两道闸，口径同 `admin/**Controller`）。
//
// ⚠️ **本模块与另两个内容域模块的一个差异**：三条**都不收 `Account`**。`comment` / `report`
//    的写端点收它是因为那几行要落作者、且要被 `IdentityGuard` 验明是本人；词表**没有作者列**
//    （DDL 就四列）⇒ 造一个只为仪式感的入参只会误导下一个人。
import { http } from './http'
import { endpoints } from './endpoints'

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。见文件头第 ③ 条。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/**
 * 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。
 * （`http.post` 返回的是裸 `Response`，见 `http.js`；拆壳收在这里，view 只管数据。）
 */
async function post(path, body) {
  return (await http.post(path, body)).json()
}

/**
 * 词表（管理端「敏感词库」）。
 *
 * @param {object} [p]
 * @param {number} [p.limit] 页大小（默认 100，上限 500）。
 * @param {number} [p.offset] 偏移（默认 0）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, offset:number}>}
 *          每项含 `Id` / `Word` / `CreatedAt`（后者是**无时区的文本**，前端按浏览器时区转）。
 *          ⚠️ **没有 `Status` / `Enabled` 这类列** —— 本表是真删（`delete` 走 `DELETE`），
 *          列表里就是「当前生效的全部词」，没有启用 / 停用两档。
 */
export function list(p) {
  return post(endpoints.sensitive.list, q({
    Limit: (p || {}).limit,
    Offset: (p || {}).offset,
  }))
}

/**
 * 加一个词 —— **并立刻扫一遍历史评论**（方案 §4.5）。
 *
 * ⚠️ **加词是有副作用的**：后端把库里所有**未删除**且含这个词的评论**就地打码**
 *    （`***` 覆写 `Content`），回体里的 `masked` 是**真被改写**的行数。
 *    ⇒ 前端要如实报给管理员（「已屏蔽 N 条历史评论」），**别**用 `success` 一个字段概括：
 *    `masked: 0`（一条都没命中）是**正常**结果，不是失败。
 *
 * ⚠️ **打码只针对老评论，不会因为老评论命中占比 ≥ 40% 就删掉它** —— 40% 那条是
 *    **发表时**的拒收规则；一条已经在库里的评论没有「拒收」这个选项（见后端
 *    `SensitiveWordService.maskOldComments` 的注释）。
 *
 * @param {string} word 词面。⚠️ 空 / 纯空白**拒**（`缺少参数：Word`）；超 **64** 字符**拒**
 *        （`Word` 列就是 `varchar(64)`）。重复 ⇒ `success:false` +「该敏感词已存在」
 *        （**业务规则**，不是故障，别弹「系统错误」）。
 * @returns {Promise<{success:boolean, Id:number, Word:string, masked:number, msg:string}>}
 */
export function add(word) {
  return post(endpoints.sensitive.add, q({ Word: word }))
}

/**
 * 删一个词。
 *
 * ⚠️ **历史评论里的 `***` 不回填**：打码是就地覆写 `Content`，原文没有第二份副本
 *    ⇒ 删词**不会**把已经变成 `***` 的评论还原。这一条要写进二次确认文案里
 *    （否则管理员会以为「删了词就恢复了」）。
 *
 * @param {string|number} id 词条 Id（`list` 里那个）。
 * @returns {Promise<{success:boolean, Id:number, msg:string}>}
 *          ⚠️ 已不存在 ⇒ `success:false` +「敏感词不存在」（并发下两个人删同一条，
 *          后到的会看到「该敏感词已被删除」）—— 都是**业务规则**。
 */
export function remove(id) {
  return post(endpoints.sensitive.delete, q({ Id: id }))
}
