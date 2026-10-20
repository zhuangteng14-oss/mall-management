// src/api/discount.js —— 折扣模板用户端 API（第三批㉖，2026-10-19）
//
// 两条**公开**端点（不判身份）：
//   · `POST /goods/discount/templates` —— 当前可用模板（商品编辑页 / 高保真预览的模板列表）
//   · `POST /goods/discount/preview`   —— 按模板反算划线价（纯计算、不写库）
//
// ⚠️ 预览**不是**落库判据：提交商品时服务端会调 `DiscountService.snapshotForSubmit`
//    重算并覆盖 `OriginalPrice`（客户端算出来的值不参与落库）。本模块只服务展示层。
//
// ⚠️ 参数口径同 `api/merchant.js` 文件头：URLSearchParams、空值不发、拆 `Response` 外壳。
import { http } from './http'
import { endpoints } from './endpoints'

/** 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

/**
 * 当前可用的平台折扣模板（`Status='active'` 且在生效窗口内，按 `SortWeight` 升序）。
 *
 * @returns {Promise<{success:boolean, list:Array<{Id:number, TemplateCode:string, Name:string,
 *          DiscountType:'rate'|'cut', Rate:number, CutAmount:number, MaxMarkupRate:number,
 *          ValidFrom:string|null, ValidTo:string|null}>}>}
 *          ⚠️ `Id` 是**数字**；预览接口的 `DiscountTemplateId` 收的也是它（不是 `TemplateCode`）。
 */
export function discountTemplates() {
  return post(endpoints.discountB4.templates, '')
}

/**
 * 按模板反算划线价（服务端权威算法）。
 *
 * @param {{DiscountTemplateId:number, Price:number, GoodType?:string}} params
 *        `Price` 必填且 > 0；`GoodType` 可选（部分模板按品类限用）。
 * @returns {Promise<{success:boolean, valid:boolean, msg:string, templateId?:number,
 *          templateName?:string, discountType?:string, discountRate?:number,
 *          discountAmount?:number, price?:number, originalPrice?:number,
 *          maxMarkupRate?:number}>}
 *          ⚠️ `valid:false` 时 `success` 也是 `false`，但 HTTP 仍是 200 ——
 *          调用方应按 `valid` 决定是否展示划线价，别只看 `success`。
 */
export function discountPreview(params) {
  return post(endpoints.discountB4.preview, q(params))
}
