package com.minke.mallmanagement.chat.model;

/**
 * 智能客服的意图枚举。
 *
 * <p>⚠️ 只做**规则匹配**，不接任何远端模型：本仓没有 LLM 依赖，短文本、领域固定的场景
 * 也不需要为一句「查订单」引入网络往返与不可控延迟。
 *
 * <p>判定顺序见 {@code ChatIntentRecognizer}：售后 &gt; 订单 &gt; 开店 &gt; 问候 &gt; 帮助 &gt; 兜底。
 * 售后排在订单前面，是因为「查订单的退款进度」同时命中两套关键词，而用户真正想知道的是退款。
 */
public enum ChatIntent {

    /** 查订单状态（最新一笔 / 指定单号 / 最近若干笔）。 */
    ORDER_QUERY,

    /** 查售后 / 退款进度。 */
    REFUND_QUERY,

    /** 怎么开店 / 商家入驻 —— 回复里带一个触发前端漫游的 action。 */
    OPEN_STORE,

    /** 问候。 */
    GREETING,

    /** 「你能做什么」类。 */
    HELP,

    /** 规则都没命中。 */
    UNKNOWN
}
