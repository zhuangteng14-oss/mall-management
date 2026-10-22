package com.minke.mallmanagement.chat.model;

/**
 * 一次身份解析的完整结果（{@code chat.model}，不是持久化实体）。
 *
 * <p>带上话术需要的全部字段，目的是让 {@code ChatAgentService} **不再二次查库**：
 * 用户名 / 商家号 / 店铺名 / 提交时间 / 驳回原因 / 品牌名 / 品牌有效期。
 *
 * <p>⚠️ {@link #identity} 在 {@link Boundary#ADMIN} 时为 {@code null}：管理员不在六态内，
 * 消费方**先看 {@link #boundary()} 再看身份**，别反过来。
 *
 * @param identity     六态身份；管理员兜底时为 {@code null}
 * @param boundary     六态之外的兜底标记（正常为 {@link Boundary#NONE}）
 * @param username     登录名（客户 / 降级商家有值；匿名与管理员为 {@code null}）
 * @param merchantId   商家业务号（商家 / 品牌商家 / 降级商家有值）
 * @param shopName     入驻申请里的店铺名（仅审核中）
 * @param submittedAt  入驻申请提交时间，形如 {@code 2026-10-20 14:00:00}（仅审核中）
 * @param rejectReason 驳回原因（仅被拒；可能为空串）
 * @param brandName    品牌名（仅品牌商家）
 * @param brandValidTo 品牌有效期，形如 {@code 2027-10-20 00:00:00}；{@code null}=长期
 */
public record ResolvedIdentity(
        UserIdentity identity,
        Boundary boundary,
        String username,
        String merchantId,
        String shopName,
        String submittedAt,
        String rejectReason,
        String brandName,
        String brandValidTo) {

    /** 六态之外的两种边界（Q3=A：只做话术兜底，不扩枚举）。 */
    public enum Boundary {
        /** 六态之内，无兜底。 */
        NONE,
        /** role=3：管理员账号，不参与买卖双方的流程。 */
        ADMIN,
        /** role=1，但最近一条 settle 申请已 approved：身份未刷新 / 已降级冻结。 */
        DEGRADED_MERCHANT
    }

    public static ResolvedIdentity anonymous() {
        return new ResolvedIdentity(UserIdentity.ANONYMOUS, Boundary.NONE,
                null, null, null, null, null, null, null);
    }

    public static ResolvedIdentity admin() {
        return new ResolvedIdentity(null, Boundary.ADMIN,
                null, null, null, null, null, null, null);
    }

    public static ResolvedIdentity noApplication(String username) {
        return new ResolvedIdentity(UserIdentity.USER_NO_APPLICATION, Boundary.NONE,
                username, null, null, null, null, null, null);
    }

    public static ResolvedIdentity pending(String username, String shopName, String submittedAt) {
        return new ResolvedIdentity(UserIdentity.USER_PENDING, Boundary.NONE,
                username, null, shopName, submittedAt, null, null, null);
    }

    public static ResolvedIdentity rejected(String username, String rejectReason) {
        return new ResolvedIdentity(UserIdentity.USER_REJECTED, Boundary.NONE,
                username, null, null, null, rejectReason, null, null);
    }

    public static ResolvedIdentity merchant(String merchantId) {
        return new ResolvedIdentity(UserIdentity.MERCHANT, Boundary.NONE,
                null, merchantId, null, null, null, null, null);
    }

    public static ResolvedIdentity brand(String merchantId, String brandName, String brandValidTo) {
        return new ResolvedIdentity(UserIdentity.MERCHANT_BRAND, Boundary.NONE,
                null, merchantId, null, null, null, brandName, brandValidTo);
    }

    /** role=1 但申请已 approved：六态仍记 MERCHANT，话术由 {@link Boundary} 改口径。 */
    public static ResolvedIdentity degradedMerchant(String username, String merchantId) {
        return new ResolvedIdentity(UserIdentity.MERCHANT, Boundary.DEGRADED_MERCHANT,
                username, merchantId, null, null, null, null, null);
    }
}
