package com.minke.mallmanagement.comment;

import java.util.Set;

/**
 * 内容域（块 6）的字面量收口 —— <b>全仓唯一一处</b>写这些点分码的地方。
 *
 * <h2>【为什么要有这个类】</h2>
 * 与 {@code TemplateTypes} / {@code AuditActions} / {@code NotificationEvents} 同源：
 * {@code comment.TargetType} / {@code Kind} / {@code Status} / {@code comment_image.Status} /
 * {@code comment_like.Type} 五组枚举值都以**字符串**落库（本仓不用 DB ENUM）。一旦在
 * Service / DAO / Controller 里各抄一份字面量，**抄错不报错** —— 写侧写 {@code "good"}、
 * 读侧筛 {@code "goods"}，结果是一个**静默空集**：列表恒空，而没有任何一行日志说得出为什么。
 * 故读写两侧一律引用本类常量。
 *
 * <h2>【口径】</h2>
 * <ul>
 *   <li><b>读侧放宽、写侧收紧</b>：{@link #knownKind} 之类只做「录入是否合法」的判定，
 *       读侧的 SQL 直接按传入值筛（老数据里若出现历史值，列表仍读得出来）。</li>
 *   <li>本类**不是 enum**（同 {@code TemplateTypes}）：落库的是字符串，enum 名字一改，
 *       库里那些行就对不上了。用 {@code public static final String} —— 值即契约。</li>
 * </ul>
 *
 * <p>⚠️ 别把 {@code TargetType='comment'} 写进来（{@code c3-modules.md §9.2} 的三种对象里
 * 有它）—— 那是**块 6 之前**的设想（对评论本身留言）；本实现用 {@code ParentId} /
 * {@code ReplyToId} 两列表达楼中楼（{@code comment} 表注释逐字写着），
 * <b>不再</b>为「回复」单独开一个 {@code TargetType}。这条差异是有意的，别当漏项补回来。
 */
public final class CommentKinds {

    private CommentKinds() {
    }

    // ==================== TargetType：评论挂在什么对象上 ====================

    /** 商品评论（买家评商品）—— 本块用户端唯一有面的一种。 */
    public static final String TARGET_GOODS = "goods";

    /** 用户留言（给某人留言）—— 后端支持、本块**无前端面**（{@code c3-modules.md §9.2} 的第二种对象）。 */
    public static final String TARGET_USER = "user";

    private static final Set<String> TARGET_TYPES = Set.of(TARGET_GOODS, TARGET_USER);

    // ==================== Kind：这一行是评价 / 提问 / 回答 ====================

    /** 评价（商品评论，顶层行）。 */
    public static final String REVIEW = "review";

    /** 提问（问答区顶层行）。 */
    public static final String QUESTION = "question";

    /** 回答（问答区的回复行，{@code ParentId} 指向提问）。 */
    public static final String ANSWER = "answer";

    private static final Set<String> KINDS = Set.of(REVIEW, QUESTION, ANSWER);

    // ==================== Status：comment.Status ====================

    /** 正常（可见）。 */
    public static final String STATUS_NORMAL = "normal";

    /** 已删除（逻辑删：作者自删 / 管理员删 / 举报处理删）。 */
    public static final String STATUS_DELETED = "deleted";

    // ==================== comment_image.Status：先审后发 ====================

    /** 待审（刚落库，前端显「审核中」）。 */
    public static final String IMAGE_PENDING = "pending";

    /** 通过（前端正常显示）。 */
    public static final String IMAGE_APPROVED = "approved";

    /** 不合规（仓库文件已删，前端显「图片违规已删除」）。 */
    public static final String IMAGE_REJECTED = "rejected";

    // ==================== comment_like.Type ====================

    /** 赞。 */
    public static final String LIKE = "like";

    /** 踩。 */
    public static final String DISLIKE = "dislike";

    // ==================== AuthorRole / 业务号 ====================

    /** 作者是客户（{@code login.Role = 1}）。 */
    public static final int ROLE_CUSTOMER = 1;

    /** 作者是商家（{@code login.Role = 2}）。 */
    public static final int ROLE_MERCHANT = 2;

    // ==================== 工具方法（写侧收紧） ====================

    /** {@code TargetType} 是否是本域认识的值（写侧判据）。 */
    public static boolean knownTargetType(String targetType) {
        return targetType != null && TARGET_TYPES.contains(targetType);
    }

    /** {@code Kind} 是否是本域认识的值（写侧判据）。 */
    public static boolean knownKind(String kind) {
        return kind != null && KINDS.contains(kind);
    }

    /**
     * {@code Kind} 归一：省略 / 空白 ⇒ {@link #REVIEW}（顶层默认是「评价」）。
     *
     * <p>⚠️ 只做「空转默认」，**不**把不认识的值悄悄折成 {@code review} —— 那会把一个
     * 拼错的 {@code "quistion"} 变成一条正常的评价行，静默吞掉调用方的错。
     * 不认识的值原样返回，由调用方按 {@link #knownKind} 拒掉。
     */
    public static String normalizeKind(String kind) {
        return (kind == null || kind.trim().isEmpty()) ? REVIEW : kind.trim();
    }

    /** 匿名 = 该行的 {@code Rating} 被强制为 5（方案 §4.1「匿名 → Rating 强制 5」）。 */
    public static final int ANONYMOUS_RATING = 5;
}
