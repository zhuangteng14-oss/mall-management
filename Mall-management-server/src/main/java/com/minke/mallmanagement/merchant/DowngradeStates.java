package com.minke.mallmanagement.merchant;

/**
 * 「商家降级」的状态机取值常量（C3 块 2 用户块）—— {@code user.DowngradeState} 列的取值域。
 *
 * <h2>【为什么是常量类而不是 DB ENUM】</h2>
 * 与 {@code AuditActions} / {@code NotificationEvents} 同一条理由：这一列会被**写入方**
 * （{@code MerchantDowngradeService} 三个转移）与**读出方**（本类的 {@link #isFrozen} 判据、
 * 前端商家端状态条、下单冻结闸）各写一遍。字面量在第二处抄错，双方都不报错 —— 写入方写
 * {@code frozen}、读出方按 {@code frozzen} 判，结果是「冻结闸永远放行」的静默失效。
 * 收成常量后，写错只剩「引用错常量」这一种，而那会同时影响两侧、当场可见。
 *
 * <p>⚠️ 与 {@code notification.EventType} 不同，**本列是封闭取值域**（只有这三态，
 * 由本块的五阶段流程穷举），故这里**不是**「登记簿」而是真约束 —— 但库层仍是
 * {@code varchar(16)} 而非 ENUM（沿用全仓「字符串码、不建 DB ENUM」的口径，
 * 见块 1 建表块注释）。
 *
 * <h2>【五阶段与三态的对映】</h2>
 * <ol>
 *   <li>管理员发起降级 ⇒ {@link #SCHEDULED}（通知期，1 天；**仍可正常经营**）</li>
 *   <li>通知期内商家收尾（下单闸**不拦** —— 只认 {@link #FROZEN}）</li>
 *   <li>到期执行 ⇒ {@link #FROZEN}（{@code role 2→1}、商品下架、冻结新单）</li>
 *   <li>30 天内重新申请通过 ⇒ {@link #NONE}（自动恢复）</li>
 *   <li>30 天后通过 ⇒ 数据仍在，需**管理员人工确认**才恢复（仍走 → {@link #NONE}）</li>
 * </ol>
 */
public final class DowngradeStates {

    /** 无降级（常态）。也是每次恢复 / 取消之后回到的值。 */
    public static final String NONE = "none";

    /** 已排定降级，处在通知期（{@code ScheduledDowngradeAt} = 到期时刻）。**不拦新单**。 */
    public static final String SCHEDULED = "scheduled";

    /** 已执行降级，处在冻结期（{@code FreezeUntil} = 窗口末端）。**拦新单**、旧单可收尾。 */
    public static final String FROZEN = "frozen";

    private DowngradeStates() {
    }
}
