package com.minke.mallmanagement.marketing.seckill.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 营销域**秒杀**的数据访问出口（C3 块 5 · commit 4）。
 *
 * <h2>【三张表都是块 5 A 组就建好的，本笔零建表、零加列】</h2>
 * 两张**流程表**（谁发起 / 谁决定）+ 一张**成立表**：
 * <ul>
 *   <li>{@code seckill_invitation} —— 路径 A：**管理端邀请 → 商家应答**（{@code invited → accepted / declined}）。</li>
 *   <li>{@code seckill_application} —— 路径 B：**商家申请 → 管理端审核**（{@code submitted → approved / rejected}）。</li>
 *   <li>{@code seckill_activity} —— 两条路径里**成立**的那条各生成一行；
 *       ⚠️ **用户端 / 主页 / 秒杀页只读这一张表**（避免 union 两张流程表）。</li>
 * </ul>
 * 两表本轮入库前均为 **0 行**（2026-10-12 在 a3 库实测）。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CouponDao} / {@code SubsidyDao}：新域一律**裸表名**。
 * 唯一破例的是 {@link #selectGoodsById} —— 它读**老域的 {@code goods} 表**，照抄老域写法带前缀。
 *
 * <h2>【⚠️ 时间窗判据比的是库时钟 {@code NOW()}，不是 {@code NOW()+8h}】</h2>
 * 三张表的 {@code ApplyEndAt} / {@code StartAt} / {@code EndAt} 都是**时刻**，写侧进库的已是 UTC 文本
 * （前端 {@code toSqlTime} 把 {@code datetime-local} 反算成 UTC）⇒ 判据必须与写侧同一个钟，
 * 即库时钟 {@code NOW()}（a3 实测 {@code @@system_time_zone='UTC'}）。
 * ⚠️ {@code NOW() + INTERVAL 8 HOUR} 那一套只属于**日历**运算（券域的「本周」/「还剩几天」），
 * 抄到时刻判据上会让活动**早开 8 小时**且**不报错** —— {@code SubsidyDao.IN_EFFECT} 栽过同一个跟头
 * （那一处 2026-10-12 已修，那里记着完整的事故经过）。
 *
 * <h2>【阶段（{@code Stage}）读侧现算，不入库】</h2>
 * 见 {@link #stage(String)}：报名期 / 未开启 / 进行中 / 已结束四档**全部由库时钟现算**——
 * 存成列就得有人定时去改它，而「谁在什么时候把它改成 live」是块 7 才有的调度问题。
 */
@Repository
public class SeckillDao {

    private final JdbcClient jdbc;

    public SeckillDao(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 阶段现算（三张表共用同一段 CASE） ====================

    /**
     * 阶段判据 —— **四档，先判报名期、再判已结束、最后分未开启 / 进行中**：
     * <ol>
     *   <li>{@code ApplyEndAt > NOW()} ⇒ {@code signup}（报名期：主页**不显示**，只有商家能报名）；</li>
     *   <li>{@code EndAt <= NOW()} ⇒ {@code ended}（已结束：哪都不显示）；</li>
     *   <li>{@code StartAt > NOW()} ⇒ {@code upcoming}（未开启：主页显示「活动未开启」占位）；</li>
     *   <li>其余 ⇒ {@code live}（进行中：主页显示秒杀模块 + 可进秒杀页）。</li>
     * </ol>
     * ⚠️ 顺序不能换：`ended` 必须排在 `upcoming` 之前 —— 若有人把 `EndAt` 填成过去的时刻，
     * 「`StartAt` 也在未来」这种情况会两边都命中，先判 `upcoming` 就会把**已经结束**的活动
     * 显示成「未开启」（用户等一个永远不会开始的活动）。
     *
     * <p>⚠️ 时间点为空的情况：{@code EndAt} 为空 ⇒ 永不 ended；{@code StartAt} 为空 ⇒ 直接 live。
     * 「没填」= **不限**，与 {@code subsidy_rule.Cap = 0} = 不封顶同一个口径。
     */
    private static String stage(String a) {
        return "CASE "
                + "WHEN " + a + ".ApplyEndAt IS NOT NULL AND " + a + ".ApplyEndAt > NOW() THEN 'signup' "
                + "WHEN " + a + ".EndAt IS NOT NULL AND " + a + ".EndAt <= NOW() THEN 'ended' "
                + "WHEN " + a + ".StartAt IS NOT NULL AND " + a + ".StartAt > NOW() THEN 'upcoming' "
                + "ELSE 'live' END AS Stage";
    }

    /** 流程表的时间列 —— 两张流程表**列名逐字相同**，故投影可以共用（只有业务号那列不同）。 */
    private static String flowTail(String a) {
        return ", DATE_FORMAT(" + a + ".ApplyEndAt, '%Y-%m-%d %H:%i:%s') AS ApplyEndAt, "
                + "DATE_FORMAT(" + a + ".StartAt, '%Y-%m-%d %H:%i:%s') AS StartAt, "
                + "DATE_FORMAT(" + a + ".EndAt, '%Y-%m-%d %H:%i:%s') AS EndAt, "
                + a + ".Status, " + a + ".Reason, "
                + "DATE_FORMAT(" + a + ".created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                + "DATE_FORMAT(" + a + ".updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at, "
                + stage(a);
    }

    private static final String INVITATION_COLUMNS =
            "i.Id, i.InvitationId, i.MerchantAccount, i.GoodsId, i.GoodName, i.OriginalPrice, "
                    + "i.SeckillPrice, i.Quantity";

    private static final String APPLICATION_COLUMNS =
            "a.Id, a.ApplicationId, a.MerchantAccount, a.GoodsId, a.GoodName, a.OriginalPrice, "
                    + "a.SeckillPrice, a.Quantity";

    private static final String ACTIVITY_COLUMNS =
            "t.Id, t.ActivityId, t.Name, t.MerchantAccount, t.GoodsId, t.GoodName, t.OriginalPrice, "
                    + "t.SeckillPrice, t.TotalStock, t.SoldStock, t.SourceKind, t.SourceId, t.Status";

    /** 活动表也有一套同名的时间列（列名与流程表逐字一致，故 tail 复用 **{@code t} 别名**）。 */
    private static final String ACTIVITY_TAIL =
            ", DATE_FORMAT(t.ApplyEndAt, '%Y-%m-%d %H:%i:%s') AS ApplyEndAt, "
                    + "DATE_FORMAT(t.StartAt, '%Y-%m-%d %H:%i:%s') AS StartAt, "
                    + "DATE_FORMAT(t.EndAt, '%Y-%m-%d %H:%i:%s') AS EndAt, "
                    + "DATE_FORMAT(t.created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                    + "DATE_FORMAT(t.updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at, "
                    + stage("t");

    // ==================== 路径 A：邀请（seckill_invitation） ====================

    public List<Map<String, Object>> selectInvitations(String status, String merchant, int limit) {
        return jdbc.sql("SELECT " + INVITATION_COLUMNS + flowTail("i")
                        + " FROM seckill_invitation i WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND i.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND i.MerchantAccount = :merchant ")
                        + "ORDER BY i.Id DESC LIMIT :limit")
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .param("limit", limit)
                .query().listOfRows();
    }

    public int countInvitations(String status, String merchant) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_invitation i WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND i.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND i.MerchantAccount = :merchant"))
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .query(Integer.class).single();
    }

    public Map<String, Object> selectInvitation(long id) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT " + INVITATION_COLUMNS + flowTail("i")
                                + " FROM seckill_invitation i WHERE i.Id = :id")
                .param("id", id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 该商家在这个商品上**还没答的**邀请条数。
     *
     * <p>⚠️ 这条守卫**只能放在 Service 层**：`seckill_invitation` 的唯一键是
     * {@code uk_seckill_invitation_id(InvitationId)} —— 业务号每一行都不一样，
     * 它**挡不住**「同商家同商品被邀请两次」（那是业务重复，不是键冲突）。
     * 靠 SQL 唯一键表达不了，只能查一次再插。
     */
    public int countOpenInvitations(String merchant, int goodsId) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_invitation i "
                        + "WHERE i.MerchantAccount = :merchant AND i.GoodsId = :goodsId "
                        + "AND i.Status = 'invited'")
                .param("merchant", merchant).param("goodsId", goodsId)
                .query(Integer.class).single();
    }

    /** 建邀请，**回新建那一行的 {@code Id}** —— 通知的 {@code TargetId} 要用它（同国补那条口径）。 */
    public int insertInvitation(String invitationId, String merchant, int goodsId, String goodName,
                                double originalPrice, double seckillPrice, int quantity,
                                String applyEndAt, String startAt, String endAt) {
        jdbc.sql("INSERT INTO seckill_invitation (InvitationId, MerchantAccount, GoodsId, GoodName, "
                        + "OriginalPrice, SeckillPrice, Quantity, ApplyEndAt, StartAt, EndAt, Status) "
                        + "VALUES (:invitationId, :merchant, :goodsId, :goodName, :originalPrice, "
                        + ":seckillPrice, :quantity, :applyEndAt, :startAt, :endAt, 'invited')")
                .param("invitationId", invitationId)
                .param("merchant", merchant)
                .param("goodsId", goodsId)
                .param("goodName", goodName)
                .param("originalPrice", originalPrice)
                .param("seckillPrice", seckillPrice)
                .param("quantity", quantity)
                // ⚠️ 空串要转 NULL：`datetime` 列收 `''` 在严格模式下会炸（同 `SubsidyDao.insertRule`）。
                .param("applyEndAt", isBlank(applyEndAt) ? null : applyEndAt)
                .param("startAt", isBlank(startAt) ? null : startAt)
                .param("endAt", isBlank(endAt) ? null : endAt)
                .update();
        return jdbc.sql("SELECT i.Id FROM seckill_invitation i WHERE i.InvitationId = :invitationId")
                .param("invitationId", invitationId).query(Integer.class).single();
    }

    /**
     * 商家应答 —— **CAS**：{@code WHERE Id AND MerchantAccount AND Status='invited'}。
     * 三条谓词缺一不可（定位行 / 挡「答别人的邀请」/ 挡「同一行答两次」），同
     * {@code SubsidyDao.casConfirm} 与提审制那条 CAS。
     */
    public int casInvitation(long id, String merchant, String status, String reason) {
        return jdbc.sql("UPDATE seckill_invitation SET Status = :status, Reason = :reason "
                        + "WHERE Id = :id AND MerchantAccount = :merchant AND Status = 'invited'")
                .param("status", status).param("reason", reason == null ? "" : reason)
                .param("id", id).param("merchant", merchant).update();
    }

    // ==================== 路径 B：申请（seckill_application） ====================

    public List<Map<String, Object>> selectApplications(String status, String merchant, int limit) {
        return jdbc.sql("SELECT " + APPLICATION_COLUMNS + flowTail("a")
                        + " FROM seckill_application a WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND a.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND a.MerchantAccount = :merchant ")
                        + "ORDER BY a.Id DESC LIMIT :limit")
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .param("limit", limit)
                .query().listOfRows();
    }

    public int countApplications(String status, String merchant) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_application a WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND a.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND a.MerchantAccount = :merchant"))
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .query(Integer.class).single();
    }

    public Map<String, Object> selectApplication(long id) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT " + APPLICATION_COLUMNS + flowTail("a")
                                + " FROM seckill_application a WHERE a.Id = :id")
                .param("id", id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该商家在这个商品上**还没审的**申请条数 —— 理由同 {@link #countOpenInvitations}（唯一键管不到业务重复）。 */
    public int countPendingApplications(String merchant, int goodsId) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_application a "
                        + "WHERE a.MerchantAccount = :merchant AND a.GoodsId = :goodsId "
                        + "AND a.Status = 'submitted'")
                .param("merchant", merchant).param("goodsId", goodsId)
                .query(Integer.class).single();
    }

    /**
     * 商家报名。
     * ⚠️ **不带三个时间点**：按块 5 口径（`b5-rule-seckill.md` §二），申请路径的时间由
     * **管理端在审核通过时**确认 ⇒ 报名这一侧三个时间列一律留 NULL，由 {@link #insertActivity} 填。
     */
    public int insertApplication(String applicationId, String merchant, int goodsId, String goodName,
                                 double originalPrice, double seckillPrice, int quantity) {
        jdbc.sql("INSERT INTO seckill_application (ApplicationId, MerchantAccount, GoodsId, GoodName, "
                        + "OriginalPrice, SeckillPrice, Quantity, Status) "
                        + "VALUES (:applicationId, :merchant, :goodsId, :goodName, :originalPrice, "
                        + ":seckillPrice, :quantity, 'submitted')")
                .param("applicationId", applicationId).param("merchant", merchant)
                .param("goodsId", goodsId).param("goodName", goodName)
                .param("originalPrice", originalPrice).param("seckillPrice", seckillPrice)
                .param("quantity", quantity).update();
        return jdbc.sql("SELECT a.Id FROM seckill_application a WHERE a.ApplicationId = :applicationId")
                .param("applicationId", applicationId).query(Integer.class).single();
    }

    /** 管理端审核 —— CAS：{@code WHERE Id AND Status='submitted'}（管理员是唯一裁决方，不比商家号）。 */
    public int casApplication(long id, String status, String reason) {
        return jdbc.sql("UPDATE seckill_application SET Status = :status, Reason = :reason "
                        + "WHERE Id = :id AND Status = 'submitted'")
                .param("status", status).param("reason", reason == null ? "" : reason)
                .param("id", id).update();
    }

    // ==================== 成立表：活动（seckill_activity） ====================

    /**
     * 「成立」的那一刻落一行活动 —— **本域唯一往用户端供给数据的地方**。
     *
     * <p>⚠️ 调用方必须**先**查过 {@link #countOverlapping}（同商品同时段只许一条成立秒杀）：
     * 那张表**没有**唯一键能表达「时间区间不重叠」（MySQL 不支持区间唯一约束），
     * 唯一键只能保证「一条报名单只成立一次」，重叠判定只能是一对 SELECT + INSERT。
     */
    public int insertActivity(String activityId, String name, String merchant, int goodsId,
                              String goodName, double originalPrice, double seckillPrice,
                              int totalStock, String applyEndAt, String startAt, String endAt,
                              String sourceKind, String sourceId) {
        jdbc.sql("INSERT INTO seckill_activity (ActivityId, Name, MerchantAccount, GoodsId, GoodName, "
                        + "OriginalPrice, SeckillPrice, TotalStock, SoldStock, ApplyEndAt, StartAt, EndAt, "
                        + "SourceKind, SourceId, Status) "
                        + "VALUES (:activityId, :name, :merchant, :goodsId, :goodName, :originalPrice, "
                        + ":seckillPrice, :totalStock, 0, :applyEndAt, :startAt, :endAt, "
                        + ":sourceKind, :sourceId, 'active')")
                .param("activityId", activityId).param("name", name)
                .param("merchant", merchant).param("goodsId", goodsId).param("goodName", goodName)
                .param("originalPrice", originalPrice).param("seckillPrice", seckillPrice)
                .param("totalStock", totalStock)
                .param("applyEndAt", isBlank(applyEndAt) ? null : applyEndAt)
                .param("startAt", isBlank(startAt) ? null : startAt)
                .param("endAt", isBlank(endAt) ? null : endAt)
                .param("sourceKind", sourceKind).param("sourceId", sourceId).update();
        return jdbc.sql("SELECT t.Id FROM seckill_activity t WHERE t.ActivityId = :activityId")
                .param("activityId", activityId).query(Integer.class).single();
    }

    /**
     * 不重叠判据：同一商品、**已成立且未停用**的活动里，有没有时间区间与
     * {@code [startAt, endAt)} 相交的。
     *
     * <p>⚠️ 两段区间相交的充要条件是 `A.Start < B.End AND A.End > B.Start` ——
     * 写成 `BETWEEN` 或只比一头都会漏（一个「完全包含」在里面的时间段照样相交）。
     */
    public int countOverlapping(int goodsId, String startAt, String endAt) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_activity t "
                        + "WHERE t.GoodsId = :goodsId AND t.Status = 'active' "
                        + "AND t.StartAt < :endAt AND t.EndAt > :startAt")
                .param("goodsId", goodsId).param("startAt", startAt).param("endAt", endAt)
                .query(Integer.class).single();
    }

    public List<Map<String, Object>> selectActivities(String status, String merchant, int limit) {
        return jdbc.sql("SELECT " + ACTIVITY_COLUMNS + ACTIVITY_TAIL
                        + " FROM seckill_activity t WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND t.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND t.MerchantAccount = :merchant ")
                        + "ORDER BY t.Id DESC LIMIT :limit")
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .param("limit", limit)
                .query().listOfRows();
    }

    public int countActivities(String status, String merchant) {
        return jdbc.sql("SELECT COUNT(*) FROM seckill_activity t WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND t.Status = :status ")
                        + (isBlank(merchant) ? "" : "AND t.MerchantAccount = :merchant"))
                .param("status", status == null ? "" : status)
                .param("merchant", merchant == null ? "" : merchant)
                .query(Integer.class).single();
    }

    public Map<String, Object> selectActivityByActivityId(String activityId) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT " + ACTIVITY_COLUMNS + ACTIVITY_TAIL
                                + " FROM seckill_activity t WHERE t.ActivityId = :activityId")
                .param("activityId", activityId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 停用活动（逻辑废弃）。{@code AND Status <> :status} ⇒ 重复停用回 {@code 0}（不是失败）。 */
    public int updateActivityStatus(String activityId, String status) {
        return jdbc.sql("UPDATE seckill_activity SET Status = :status "
                        + "WHERE ActivityId = :activityId AND Status <> :status")
                .param("status", status).param("activityId", activityId).update();
    }

    /**
     * 用户端：**进行中和未开启**的成立活动（已结束 / 已停用 / 商品下架 一律不出现）。
     *
     * <p>⚠️ 这里**不写 `StartAt <= NOW()`**：主页要拿 {@code upcoming} 那一档显示「活动未开启」占位，
     * 所以「还没开始」的行也得回；判 {@code live} 还是 {@code upcoming} 由 {@code Stage} 列说
     * （前端按 {@code Stage} 分流，不自己比时间）。⚠️ 但 {@code ended} 的一律挡掉 —— 那是垃圾行。
     *
     * <p>⚠️ 三处 JOIN / 谓词各挡一件事：{@code t.Status='active'} 挡停用、
     * {@code t.EndAt > NOW()} 挡已结束、{@code g} 那一侧挡商品下架或已删 ——
     * 少了任一条，就会出现「秒杀价还在、东西已经不能买」。
     */
    public List<Map<String, Object>> selectLive(int limit) {
        return jdbc.sql("SELECT " + ACTIVITY_COLUMNS + ACTIVITY_TAIL
                        + ", g.GoodImg, g.GoodType, g.ProductCode "
                        + "FROM seckill_activity t "
                        + "JOIN mall_management_sql.goods g ON g.Id = t.GoodsId "
                        + "WHERE t.Status = 'active' AND (t.EndAt IS NULL OR t.EndAt > NOW()) "
                        + "AND g.ShelfState = 1 AND g.DeletedAt IS NULL "
                        + "ORDER BY t.StartAt ASC LIMIT :limit")
                .param("limit", limit).query().listOfRows();
    }

    /** 老域 {@code goods} 表的单行读 —— 建邀请 / 报名时取原价与商家号快照（带前缀，同 {@code GoodsDao}）。 */
    public Map<String, Object> selectGoodsById(int goodsId) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT Id, MerchantId, GoodName, Price, GoodType, ProductCode "
                                + "FROM mall_management_sql.goods WHERE Id = :id AND DeletedAt IS NULL")
                .param("id", goodsId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
