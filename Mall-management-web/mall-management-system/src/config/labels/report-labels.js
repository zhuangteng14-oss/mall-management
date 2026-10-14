// src/config/labels/report-labels.js —— **审核 / 举报域**的中文标签层（C3 块 7 · commit 9，2026-10-14）。
//
// ⚠️ 【这里其实是**两张表**，别当成一张】举报单与评论图各有自己的三态，**取值集合不同**：
//    ① `REPORT_STATUS_LABEL` ← 后端 `comment/service/ReportService.java`：`pending` / `handled` / `rejected`
//       （注意中档是 **handled**，不是 approved）。
//    ② `IMAGE_STATUS_LABEL`  ← 后端评论图审核：`pending` / `approved` / `rejected`
//       （注意中档是 **approved**，不是 handled）。
//    ⇒ 两者有两个键同名（`pending` / `rejected`）而中档不同名 —— **正是最容易抄错的地方**：
//      拿举报的那张去翻评论图，`approved` 会翻不出来、退回显示英文（兜底救了它，但页面上就露了）。
//
// ⚠️ 【兜底】认不出的状态**原样回 code**（同各表口径）：后端加了第四态而这里没跟，
//    页面应当显示一个看得见的英文状态，不是空白。
//
// ⚠️ 【本页原来那两份局部表已收拢至此】`ModerationPage.vue` 里原有一份 `IMAGE_STATUSES`
//    （带 `cap` 那个字段，用来拼 `counts` 的键名 `imagePending`…）与一个 `statusLabel`。
//    commit 9 把**中文**搬到这里（`cap` 是拼键名用的**结构**字段，留在页面）。

/**
 * 举报单状态 → 中文。取值逐条对齐后端 `ReportService`（`pending` 待处理 / `handled` 已处理 /
 * `rejected` 已驳回）。
 */
export const REPORT_STATUS_LABEL = {
  pending: '待处理',
  handled: '已处理',
  rejected: '已驳回',
}

/** 评论图审核状态 → 中文。取值对齐后端图片审核三态（中档是 `approved`，与举报的 `handled` 不同）。 */
export const IMAGE_STATUS_LABEL = {
  pending: '待审',
  approved: '已通过',
  rejected: '已驳回',
}

/** 取举报状态中文（认不出回落 code 原文）。 */
export function reportStatusLabel(status) {
  return Object.prototype.hasOwnProperty.call(REPORT_STATUS_LABEL, status)
    ? REPORT_STATUS_LABEL[status]
    : String(status == null ? '' : status)
}

/** 取图片状态中文（认不出回落 code 原文）。⚠️ 别拿 `reportStatusLabel` 代这一条，两张表不同键。 */
export function imageStatusLabel(status) {
  return Object.prototype.hasOwnProperty.call(IMAGE_STATUS_LABEL, status)
    ? IMAGE_STATUS_LABEL[status]
    : String(status == null ? '' : status)
}
