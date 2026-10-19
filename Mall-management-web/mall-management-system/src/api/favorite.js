// src/api/favorite.js —— 商品收藏用户端 API（2026-10-19 终局清道夫第 7 条）
//
// 四条端点，全部收 `UserId`（业务号：`customerid` → `merchantid` → 登录名兜底，
// 见 `api/auth.js#getMemberId`）：
//   · `POST /favorite/add`    —— 收藏（唯一键幂等，重复点不产生第二行）
//   · `POST /favorite/remove` —— 取消收藏（本来没收藏也不报错）
//   · `POST /favorite/list`   —— 收藏列表（`limit` 可选，默认 100 / 上限 200）
//   · `POST /favorite/check`  —— 查某件商品收藏没有（详情页心形按钮的初始态）
//
// ⚠️ 参数口径同全仓 `api/*.js`：路径取自 `endpoints.js`、发 `URLSearchParams` 表单串
//    （后端收 `@RequestParam`，发 JSON 会静默不绑定）、空值一律不发。
// ⚠️ 后端每个端点第一行过 `IdentityGuard.requireSelfOrAdmin`：`UserId` 必须是调用者自己，
//    管理员例外 —— 传别人的号会 403，这是刻意的。
import { http } from './http'
import { endpoints } from './endpoints'

function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

async function post(path, body) {
  return (await http.post(path, body)).json()
}

/** 收藏一件商品。返回 `{success, favorited:true, added:boolean, msg}`。 */
export function favoriteAdd(userId, goodsId) {
  return post(endpoints.favorite.add, q({ UserId: userId, GoodsId: goodsId }))
}

/** 取消收藏。返回 `{success, favorited:false, removed:boolean, msg}`。 */
export function favoriteRemove(userId, goodsId) {
  return post(endpoints.favorite.remove, q({ UserId: userId, GoodsId: goodsId }))
}

/** 收藏列表。返回 `{success, list, total, limit}`；`list` 已左连 `goods`。 */
export function favoriteList(userId, limit) {
  return post(endpoints.favorite.list, q({ UserId: userId, limit }))
}

/** 查收藏状态。返回 `{success, favorited:boolean}`。 */
export function favoriteCheck(userId, goodsId) {
  return post(endpoints.favorite.check, q({ UserId: userId, GoodsId: goodsId }))
}
