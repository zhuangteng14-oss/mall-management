package com.minke.mallmanagement.chat.model;

/**
 * 智能客服看到的**六态身份**（2026-10-22 增量-A / 决策 Q1=A）。
 *
 * <p>为什么是六态而不是「商家 / 用户」二值：入驻审核是一条有中间态的长流程，
 * 只说「你是用户」会让正在审核、被驳回的人再问一遍「我到底算什么」。六态各自对应
 * 一句可纠错的话术（见 {@code ChatAgentService#identitySentence}），用户看到系统判断
 * 之后能直接说「不对」，而不是被反问「您是商家还是用户」。
 *
 * <p>⚠️ <b>本枚举**刻意只有六值**</b>：管理员（role=3）与「已降级/冻结商家」都在六态
 * 之外，由 {@code ResolvedIdentity.Boundary} 做**话术兜底**，不在这里扩成八态（Q3=A）。
 */
public enum UserIdentity {

    /** 未登录，或会话里的业务号查不到人。 */
    ANONYMOUS,

    /** 普通客户，且从未提交过入驻申请（settle）。 */
    USER_NO_APPLICATION,

    /** 普通客户，最近一条入驻申请处于 {@code pending}。 */
    USER_PENDING,

    /** 普通客户，最近一条入驻申请处于 {@code rejected}。 */
    USER_REJECTED,

    /** 商家（role=2，且无有效品牌档案）。 */
    MERCHANT,

    /** 品牌商家：role=2，且 {@code merchant_brand_profile} 为 active 且未过期。 */
    MERCHANT_BRAND
}
