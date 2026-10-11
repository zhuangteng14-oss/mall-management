package com.minke.mallmanagement.template;

import java.util.Set;

/**
 * 通用模板表 {@code template} 的 **{@code Type} 取值域**（C3 块 3 就地改造，2026-10-10）。
 *
 * <h2>【为什么要有这个类】</h2>
 * 与 {@code AuditActions} / {@code NotificationEvents} **逐字同因**：{@code Type} 这个字符串码
 * 会被**写入方**（管理端建模板、迁移脚本搬 {@code notification_template}）与**读出方**
 * （各域弹窗「选模板」按钮按 type 拉列表、公共读端点按 type 取一条）各写一遍。
 * 字面量在第二处被抄错时**双方都不报错**，只会得到「一条也查不出来」的静默空集。
 *
 * <h2>【取值口径：点分字符串码，不建 DB ENUM】</h2>
 * 同 {@code goods_audit.Status} / {@code AuditActions} 的口径。域 = 前缀，动作/位置 = 后缀：
 * <ul>
 *   <li><b>商品类</b>（{@code goods.*}）：{@link #GOODS_DETAIL} / {@link #GOODS_SPEC} /
 *       {@link #GOODS_AFTER_SALE} —— 商家上架时「选模板 → 填进输入框 → 可改 → 提交」，
 *       {@code Body} 只是**快照的起点**，落库的是商品自己那一行文本。</li>
 *   <li><b>驳回/封禁理由类</b>：{@link #GOODS_REJECT} / {@link #MERCHANT_REJECT} /
 *       {@link #MERCHANT_APPROVE} / {@link #USER_BAN} —— 管理端在理由输入框旁的「选模板」。</li>
 *   <li><b>评论回复</b>：{@link #COMMENT_REPLY} —— ⚠️ **块 6 才建行**，本次只定义常量
 *       （见方案 §七 边界 3）。</li>
 *   <li><b>通知类</b>（{@link #NOTIFICATION_PREFIX}）：{@code notification.<原 TemplateKey>}，
 *       由 {@link #notification(String)} 拼出。原 {@code notification_template} 的行在迁移块里
 *       就是这么搬过来的。⚠️ 一并搬过来的还有块 1 立下的口径：**占位符渲染与「send 时按 key 取模板」
 *       本次仍不做**。</li>
 * </ul>
 *
 * <h2>【⚠️ 列宽】</h2>
 * {@code template.Type} 是 {@code varchar(96)} —— 上界由通知类给出：
 * {@code 'notification.'}(13) + {@code TemplateKey} 上界(64) = 77 ⇒ 取 96 留余量。
 * ⚠️ 首版曾定 {@code varchar(32)}，实测**装不下**（最长真实 key
 * {@code merchant.downgrade.scheduled} ⇒ 41 字符，写入报 1406），见迁移块「① 续加宽守卫」。
 */
public final class TemplateTypes {

    /** 商品详情模板 —— {@code GoodsAdd.vue} 详情输入框。 */
    public static final String GOODS_DETAIL = "goods.detail";

    /** 商品规格模板 —— {@code GoodsAdd.vue} 规格输入框。 */
    public static final String GOODS_SPEC = "goods.spec";

    /** 商品售后模板 —— {@code GoodsAdd.vue} 售后输入框。 */
    public static final String GOODS_AFTER_SALE = "goods.afterSale";

    /** 商品提审驳回理由模板 —— {@code CatalogPage.vue} 审核弹窗。 */
    public static final String GOODS_REJECT = "goods.reject";

    /** 商家入驻驳回理由模板 —— {@code AccountsPage.vue} 入驻审核弹窗。 */
    public static final String MERCHANT_REJECT = "merchant.reject";

    /**
     * 商家入驻通过理由模板 —— ⚠️ 当前**没有消费面**（页面上没有「通过理由」输入框），
     * 只定义常量，见方案 §七 边界 4。
     */
    public static final String MERCHANT_APPROVE = "merchant.approve";

    /** 用户封禁理由模板 —— {@code AccountsPage.vue} 封禁弹窗。 */
    public static final String USER_BAN = "user.ban";

    /** 评论回复模板 —— ⚠️ **块 6 才建行**，本次只定义常量。 */
    public static final String COMMENT_REPLY = "comment.reply";

    /**
     * 通知类模板的 type 前缀 —— 合并前 {@code notification_template.TemplateKey} 的行
     * 在迁移块里一律落成 {@link #NOTIFICATION_PREFIX} + 原 key。
     */
    public static final String NOTIFICATION_PREFIX = "notification.";

    /**
     * 模板形态（{@code template.Kind}，2026-10-11 加）—— **文本模板**：填一段话
     * （详情 / 售后 / 各类理由），落进 {@code Body} 后原样展示。
     */
    public static final String KIND_TEXT = "text";

    /**
     * 模板形态 —— **结构化模板**：正文是**结构的定义**而非一段话。当前唯一用途是
     * {@link #GOODS_SPEC} 下的多规格矩阵：正文每行一条轴、写作 {@code 轴名=值1,值2,…}，
     * 商家选它 → 前端按各轴取值做**笛卡尔积** → 生成矩阵行（行列结构来自模板）。
     */
    public static final String KIND_STRUCTURED = "structured";

    /** 固定取值域（不含 {@code notification.*} —— 那一支是**动态**的，靠前缀判）。 */
    private static final Set<String> FIXED = Set.of(
            GOODS_DETAIL, GOODS_SPEC, GOODS_AFTER_SALE,
            GOODS_REJECT, MERCHANT_REJECT, MERCHANT_APPROVE, USER_BAN,
            COMMENT_REPLY);

    /** {@code notification.<原 TemplateKey>} —— 迁移块与 NotifyPage 共用这一个拼法。 */
    public static String notification(String templateKey) {
        return NOTIFICATION_PREFIX + templateKey;
    }

    /**
     * 是否已知的 type（固定集 ∪ {@code notification.} 前缀）。
     *
     * <p>⚠️ <b>只给「管理端建模板」用</b>：公共读端点{@code /template/list}、{@code /get}
     * **不校验**未知 type —— 传个没见过的 type 返回空列表，而不是报错（读侧放宽，写侧收紧）。
     */
    public static boolean known(String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim();
        return FIXED.contains(t)
                || (t.startsWith(NOTIFICATION_PREFIX) && t.length() > NOTIFICATION_PREFIX.length());
    }

    /**
     * 把写侧传来的 {@code kind} 归一成**已知取值**之一：是 {@link #KIND_STRUCTURED} 就用它，
     * **其余一律回落 {@link #KIND_TEXT}**（含 {@code null} / 空串 / 没见过的值）。
     *
     * <p>⚠️ 与 {@link #known(String)} 的「读侧放宽、写侧收紧」同口径，但**更宽**：未知 kind
     * **不报错**，直接当文本模板 —— 因为 kind 只是「怎么用正文」的提示，认不出就当最朴素的文本，
     * 不会让管理端建不出模板。
     */
    public static String normalizeKind(String kind) {
        return KIND_STRUCTURED.equals(kind == null ? null : kind.trim()) ? KIND_STRUCTURED : KIND_TEXT;
    }

    /**
     * 是否**必须有 Title**。
     *
     * <p>通知类是「标题 + 正文」两段模板（原 {@code notification_template} 两列都 NOT NULL）；
     * 商品类 / 理由类的 Title 无意义（{@code Body} 才是内容）⇒ 允许为空。
     */
    public static boolean requiresTitle(String type) {
        return type != null && type.trim().startsWith(NOTIFICATION_PREFIX);
    }

    private TemplateTypes() {
    }
}
