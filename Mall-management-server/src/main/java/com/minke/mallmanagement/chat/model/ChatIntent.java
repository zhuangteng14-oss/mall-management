package com.minke.mallmanagement.chat.model;

/**
 * 智能客服的意图枚举。
 *
 * <p>⚠️ 只做**规则匹配**，不接任何远端模型：本仓没有 LLM 依赖，短文本、领域固定的场景
 * 也不需要为一句「查订单」引入网络往返与不可控延迟。
 *
 * <p>判定顺序见 {@code ChatIntentRecognizer}：售后 &gt; 订单 &gt; 秒杀 &gt; 开店 &gt; 身份自述
 * &gt; 问候 &gt; 帮助 &gt; 兜底。售后排在订单前面，是因为「查订单的退款进度」同时命中两套
 * 关键词，而用户真正想知道的是退款；秒杀排在开店前面，是因为「上架到秒杀」也含「上架」。
 */
public enum ChatIntent {

    /** 查订单状态（最新一笔 / 指定单号 / 最近若干笔）。 */
    ORDER_QUERY,

    /** 查售后 / 退款进度。 */
    REFUND_QUERY,

    /** 怎么开店 / 商家入驻 —— 回复里带一个触发前端漫游的 action。 */
    OPEN_STORE,

    /** 报名秒杀 / 参加平台活动 —— 只导航 + 六态分支，不猜商品与价格（N3 / Q5=A）。 */
    ADD_TO_SECKILL,

    /**
     * 页面级导航（NavGraph，2026-10-22）：大模型只把用户的话映射到
     * {@link com.minke.mallmanagement.chat.service.RouteCatalog} 里的一个真实路径
     * （{@code target_path}）；「怎么走过去」由前端 BFS 计算，后端不返回步骤。
     */
    NAVIGATE,

    /** 用户自述身份（「我是商家」）—— 用于对照系统解析结果，不盲信（增量-A 规则 2）。 */
    IDENTITY_CLAIM,

    /** 问候。 */
    GREETING,

    /** 「你能做什么」类。 */
    HELP,

    /** 规则没命中，但命中了 FAQ 知识库（增量「轻量知识库」）。 */
    FAQ,

    /** 规则都没命中、FAQ 也没命中。 */
    UNKNOWN
}
