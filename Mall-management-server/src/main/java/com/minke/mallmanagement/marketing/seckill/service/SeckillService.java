package com.minke.mallmanagement.marketing.seckill.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.marketing.seckill.dao.SeckillDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 秒杀业务服务（C3 块 5 · commit 4）—— 双向制：**管理端邀请 / 商家申请**，两条路径各自成立。
 *
 * <h2>【两条路径、发起方与决定方相反】</h2>
 * <ul>
 *   <li><b>路径 A 邀请</b>：管理员发起（{@link #invite}）→ **商家**决定（{@link #respond}）。</li>
 *   <li><b>路径 B 申请</b>：商家发起（{@link #apply}）→ **管理员**决定（{@link #review}）。</li>
 * </ul>
 * ⚠️ **没有第三条「管理端单向指定」**：那会让商家在不知情的情况下被挂上秒杀价
 * （`docs/agent/c3-modules.md` §9.1 的硬口径）。
 *
 * <h2>【两张流程表 + 一张成立表 —— 用户端只读成立表】</h2>
 * {@code accepted} / {@code approved} 的那一刻各生成一行 {@code seckill_activity}，由
 * {@link #createActivity} 一个出口落库。用户端 / 主页 / 秒杀页**只读活动表**，
 * 不必 union 两张流程表（那是 `b5-tables-A.md` 取法甲的裁决）。
 *
 * <h2>【本类不写通知】</h2>
 * 同 {@code SubsidyService}：旁路副作用归 **Controller**（那是「业务已成、再发通知」的位置，
 * 而 {@code NotificationService.send} 自己 fail-soft）。故各方法回体里**带上收件人要用的字段**
 * （{@code MerchantAccount} / {@code GoodName} / {@code Id}），Controller 直接读，不必回头再查库。
 *
 * <h2>【时间点是**时刻**，一律 UTC 文本进库】</h2>
 * 前端 {@code datetime-local} → UTC 的过程在页面侧（{@code toSqlTime}）；
 * 本类只做**格式**与**先后**校验，不做时区换算 —— 换算是页面一处的事，
 * 后端再来一次就会变成两个真源。
 */
@Service
public class SeckillService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(SeckillService.class);

    public static final int DEFAULT_LIMIT = PageLimits.DEFAULT_PAGE_SIZE;

    public static final int MAX_LIMIT = PageLimits.MAX_PAGE_SIZE;

    /** {@code seckill_invitation.Status} 取值域（与建表注释逐字一致）。 */
    public static final String INVITED = "invited";

    public static final String ACCEPTED = "accepted";

    public static final String DECLINED = "declined";

    /** {@code seckill_application.Status} 取值域。 */
    public static final String SUBMITTED = "submitted";

    public static final String APPROVED = "approved";

    public static final String REJECTED = "rejected";

    /** {@code seckill_activity.Status} 取值域。 */
    public static final String ACTIVE = "active";

    public static final String STOPPED = "stopped";

    /** 两条路径的代号 —— 落 {@code seckill_activity.SourceKind}。 */
    public static final String SOURCE_INVITATION = "invitation";

    public static final String SOURCE_APPLICATION = "application";

    /** {@code datetime} 列的入参形状（与 {@code SubsidyService.DATETIME} 同款）。 */
    private static final Pattern DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?$");

    private final SeckillDao seckillDao;

    public SeckillService(SeckillDao seckillDao) {
        this.seckillDao = seckillDao;
    }

    // ==================== 路径 A：邀请 ====================

    public Map<String, Object> invitations(String status, String merchant, Integer limit) {
        String st = normalizeInvitationStatus(status);
        if (st == null && trimToNull(status) != null) {
            return fail("Status 只认 invited / accepted / declined");
        }
        int effective = normalizeLimit(limit);
        String m = trimToNull(merchant);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", seckillDao.selectInvitations(st, m, effective));
        result.put("total", seckillDao.countInvitations(st, m));
        result.put("limit", effective);
        return result;
    }

    /**
     * 管理端邀请商家上秒杀。
     *
     * <p>校验顺序 = **越便宜的越先**：参数 → 商品存在 → **归属**（商品必属被邀请的商家）→
     * 秒杀价 < 原价 → 时间先后 → 有没有还没答的同商品邀请 → 才写库。
     *
     * @param seckillPriceRaw 秒杀价；必须 {@code > 0} 且 {@code < 原价}
     * @param quantityRaw     声明限量；{@code 0} / 省略 = **不限量**（与「Cap=0 不封顶」同一个「没填≠填0」的口径会打架，
     *                        故这里明说：本域的 0 就是 **0 件 = 售完**，见下面的 ⚠️）
     */
    public Map<String, Object> invite(String merchant, Integer goodsId, String seckillPriceRaw,
                                      String quantityRaw, String applyEndAt, String startAt, String endAt) {
        String m = trimToNull(merchant);
        if (m == null || goodsId == null || goodsId <= 0) {
            return fail("缺少参数：MerchantAccount / GoodsId");
        }
        Map<String, Object> goods = seckillDao.selectGoodsById(goodsId);
        if (goods == null) {
            return fail("商品不存在或已删除");
        }
        // ⚠️ 归属校验：不判这一条，管理员就能把 A 店的商品挂到 B 店名下 —— 秒杀价是**成交价**，
        //    挂错店等于让别人替你打折（`b5-rule-seckill.md` §四 明写「商品必属发起商家」）。
        if (!m.equals(str(goods.get("MerchantId")))) {
            return fail("这件商品不属于该商家");
        }
        Double originalPrice = toDouble(goods.get("Price"));
        Double price = parseDouble(seckillPriceRaw);
        if (originalPrice == null || originalPrice <= 0) {
            return fail("商品原价异常，无法上秒杀");
        }
        String priceError = checkPrice(price, originalPrice);
        if (priceError != null) {
            return fail(priceError);
        }
        Double qty = parseDouble(quantityRaw, 0d);
        if (qty == null || qty < 0 || qty != Math.rint(qty)) {
            return fail("限量必须是非负整数");
        }
        String timeError = checkWindow(applyEndAt, startAt, endAt, true);
        if (timeError != null) {
            return fail(timeError);
        }
        // ⚠️ 「同商家同商品还有没答的邀请」—— 唯一键管不到这条（见 `SeckillDao.countOpenInvitations`）。
        if (seckillDao.countOpenInvitations(m, goodsId) > 0) {
            return fail("该商家这个商品已有一条待应答的邀请");
        }
        String invitationId = newBizId("SKI-");
        int id;
        try {
            id = seckillDao.insertInvitation(invitationId, m, goodsId, str(goods.get("GoodName")),
                    originalPrice, price, qty.intValue(),
                    trimToNull(applyEndAt), trimToNull(startAt), trimToNull(endAt));
        } catch (Exception ex) {
            ex.printStackTrace();
            return fail("邀请失败：" + ex.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已邀请，等待商家应答");
        // ⚠️ Id = **seckill_invitation 那一行的主键** —— Controller 拿它作通知 TargetId，
        //    商家的「同意 / 拒绝」按钮才有东西可打（同国补提名那条口径）。
        result.put("Id", id);
        result.put("InvitationId", invitationId);
        result.put("MerchantAccount", m);
        result.put("GoodsId", goodsId);
        result.put("GoodName", goods.get("GoodName"));
        result.put("OriginalPrice", originalPrice);
        result.put("SeckillPrice", price);
        return result;
    }

    /**
     * 商家应答邀请。
     *
     * <p>同意 ⇒ **当场成立一场活动**（{@link #createActivity}）；拒绝 ⇒ 只翻状态 + 记理由。
     * 第二次点回 {@code already-handled}（不是失败，也不许再改状态）。
     */
    public Map<String, Object> respond(String merchant, Long id, Boolean accept, String reason) {
        String m = trimToNull(merchant);
        if (m == null || id == null || id <= 0) {
            return fail("缺少参数：MerchantAccount / Id");
        }
        boolean yes = Boolean.TRUE.equals(accept);
        String r = reason == null ? "" : reason.trim();
        // ⚠️ 拒绝必须给理由，且**一条 SQL 都不发** —— 校验在取快照之前（同国补确认那条）。
        if (!yes && r.isEmpty()) {
            return fail("拒绝时必须填写理由");
        }
        Map<String, Object> before = seckillDao.selectInvitation(id);
        if (before == null) {
            return fail("邀请不存在");
        }
        if (!m.equals(str(before.get("MerchantAccount")))) {
            return fail("这条邀请不属于当前商家");
        }
        // ⚠️ 「已经答过」必须排在**两道闸之前**（2026-10-12 手验 B 组实测后补）：
        //    上面那条「两道闸排在 CAS 之前」是为了「成立前先闸完」，代价是 CAS 再也发现不了
        //    「重复点击」—— 第二次点同意时，时段重叠那道闸会**先**命中，把一句
        //    「该商品在这个时段已有一场秒杀」报给一个只是重复点了一下的人（就是他刚点出来的那场）。
        //    javadoc 承诺的 `already-handled` 因此永远回不去 ⇒ 显式看一眼状态，排在最前。
        if (!INVITED.equals(str(before.get("Status")))) {
            return alreadyHandled(id);
        }
        // ⚠️ 「成立前的两道闸」必须排在 CAS **之前**：CAS 一翻成 accepted 就回不了头
        //    （这时再报错，库里会留一条 treated-as-accepted 却没有活动的行）。
        //    两件事都能在写库前判死：窗口齐全、时段不重叠。
        String startAt = null;
        String endAt = null;
        if (yes) {
            startAt = str(before.get("StartAt"));
            endAt = str(before.get("EndAt"));
            // 管理员建邀请时若没给时间，这场活动**无法判阶段**（Stage 全靠三个时间点算）
            // ⇒ 宁可在这里明确拒掉，也不落一行永远不知何时开始的邀请。
            if (startAt == null || endAt == null) {
                return fail("该邀请没有开始 / 结束时间，无法成立（请让管理员补上后重新邀请）");
            }
            if (seckillDao.countOverlapping(intOf(before.get("GoodsId")), startAt, endAt) > 0) {
                return fail("该商品在这个时段已有一场秒杀，不能重叠");
            }
        }
        int updated = seckillDao.casInvitation(id, m, yes ? ACCEPTED : DECLINED, yes ? "" : r);
        if (updated == 0) {
            return alreadyHandled(id);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("Id", id);
        result.put("MerchantAccount", m);
        result.put("GoodName", before.get("GoodName"));
        result.put("Reason", r);
        if (!yes) {
            result.put("outcome", "declined");
            result.put("msg", "已拒绝");
            return result;
        }
        // 同意 ⇒ 成立（两道闸已在 CAS 之前过完，见上）。
        Map<String, Object> created = createActivity(null, m, intOf(before.get("GoodsId")),
                str(before.get("GoodName")), dblOf(before.get("OriginalPrice")),
                dblOf(before.get("SeckillPrice")), intOf(before.get("Quantity")),
                str(before.get("ApplyEndAt")), startAt, endAt,
                SOURCE_INVITATION, str(before.get("InvitationId")));
        if (!Boolean.TRUE.equals(created.get("success"))) {
            return created;
        }
        result.put("outcome", "accepted");
        result.put("msg", "已同意，秒杀活动已成立");
        result.put("ActivityId", created.get("ActivityId"));
        return result;
    }

    // ==================== 路径 B：申请 ====================

    public Map<String, Object> applications(String status, String merchant, Integer limit) {
        String st = normalizeApplicationStatus(status);
        if (st == null && trimToNull(status) != null) {
            return fail("Status 只认 submitted / approved / rejected");
        }
        int effective = normalizeLimit(limit);
        String m = trimToNull(merchant);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", seckillDao.selectApplications(st, m, effective));
        result.put("total", seckillDao.countApplications(st, m));
        result.put("limit", effective);
        return result;
    }

    /** 商家报名（不带时间点 —— 时间由管理端审核时确认，见类注释）。 */
    public Map<String, Object> apply(String merchant, Integer goodsId, String seckillPriceRaw,
                                     String quantityRaw) {
        String m = trimToNull(merchant);
        if (m == null || goodsId == null || goodsId <= 0) {
            return fail("缺少参数：MerchantAccount / GoodsId");
        }
        Map<String, Object> goods = seckillDao.selectGoodsById(goodsId);
        if (goods == null) {
            return fail("商品不存在或已删除");
        }
        if (!m.equals(str(goods.get("MerchantId")))) {
            return fail("这件商品不属于当前商家");
        }
        Double originalPrice = toDouble(goods.get("Price"));
        Double price = parseDouble(seckillPriceRaw);
        if (originalPrice == null || originalPrice <= 0) {
            return fail("商品原价异常，无法报名秒杀");
        }
        String priceError = checkPrice(price, originalPrice);
        if (priceError != null) {
            return fail(priceError);
        }
        Double qty = parseDouble(quantityRaw, 0d);
        if (qty == null || qty < 0 || qty != Math.rint(qty)) {
            return fail("限量必须是非负整数");
        }
        if (seckillDao.countPendingApplications(m, goodsId) > 0) {
            return fail("该商品已有一条待审核的秒杀申请");
        }
        String applicationId = newBizId("SKA-");
        int id;
        try {
            id = seckillDao.insertApplication(applicationId, m, goodsId, str(goods.get("GoodName")),
                    originalPrice, price, qty.intValue());
        } catch (Exception ex) {
            ex.printStackTrace();
            return fail("报名失败：" + ex.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已提交秒杀申请，等待管理端审核");
        result.put("Id", id);
        result.put("ApplicationId", applicationId);
        result.put("MerchantAccount", m);
        result.put("GoodsId", goodsId);
        result.put("GoodName", goods.get("GoodName"));
        result.put("OriginalPrice", originalPrice);
        result.put("SeckillPrice", price);
        return result;
    }

    /**
     * 管理端审核申请。
     *
     * <p>⚠️ **通过时必须给出开始 / 结束时间** —— 申请路径的时间点是「管理端在审核时确认」
     * （`b5-rule-seckill.md` §二），不是商家报名时带的。缺时间 ⇒ 直接拒，理由同
     * {@link #respond} 那条：没有窗口的活动判不出阶段。
     *
     * @param name 活动名；留空 ⇒ 回落到商品名（流程表没有 Name 列，见下面 createActivity 的 ⚠️）
     */
    public Map<String, Object> review(Long id, Boolean approve, String reason, String name,
                                      String applyEndAt, String startAt, String endAt) {
        if (id == null || id <= 0) {
            return fail("缺少参数：Id");
        }
        boolean yes = Boolean.TRUE.equals(approve);
        String r = reason == null ? "" : reason.trim();
        if (!yes && r.isEmpty()) {
            return fail("驳回时必须填写理由");
        }
        Map<String, Object> before = seckillDao.selectApplication(id);
        if (before == null) {
            return fail("申请不存在");
        }
        // ⚠️ 同 {@link #respond}：`already-handled` 要排在「重叠 / 窗口」两道闸之前，
        //    否则重复审核会被那句「该商品在这个时段已有一场秒杀」冒充。
        if (!SUBMITTED.equals(str(before.get("Status")))) {
            return alreadyHandled(id);
        }
        if (yes) {
            String timeError = checkWindow(applyEndAt, startAt, endAt, false);
            if (timeError != null) {
                return fail(timeError);
            }
            // 同 respond：重叠判定排在 CAS 之前（CAS 之后就没有干净的回退路径了）。
            if (seckillDao.countOverlapping(intOf(before.get("GoodsId")),
                    trimToNull(startAt), trimToNull(endAt)) > 0) {
                return fail("该商品在这个时段已有一场秒杀，不能重叠");
            }
        }
        int updated = seckillDao.casApplication(id, yes ? APPROVED : REJECTED, yes ? "" : r);
        if (updated == 0) {
            return alreadyHandled(id);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("Id", id);
        result.put("MerchantAccount", before.get("MerchantAccount"));
        result.put("GoodName", before.get("GoodName"));
        result.put("Reason", r);
        // ⚠️ 业务串要回带：管理端留痕的 `admin_audit.TargetId` 记的是它（不是自增 Id）——
        //    同国补提名那条口径，也让「审计里的 TargetId」与「通知里的 TargetType/TargetId」同族。
        result.put("ApplicationId", before.get("ApplicationId"));
        if (!yes) {
            result.put("outcome", "rejected");
            result.put("msg", "已驳回");
            return result;
        }
        Map<String, Object> created = createActivity(trimToNull(name),
                str(before.get("MerchantAccount")), intOf(before.get("GoodsId")),
                str(before.get("GoodName")), dblOf(before.get("OriginalPrice")),
                dblOf(before.get("SeckillPrice")), intOf(before.get("Quantity")),
                trimToNull(applyEndAt), trimToNull(startAt), trimToNull(endAt),
                SOURCE_APPLICATION, str(before.get("ApplicationId")));
        if (!Boolean.TRUE.equals(created.get("success"))) {
            return created;
        }
        result.put("outcome", "approved");
        result.put("msg", "已通过，秒杀活动已成立");
        result.put("ActivityId", created.get("ActivityId"));
        // ⚠️ 回带**生效后**的活动名（留空时已回落商品名）—— Controller 的留痕 `after.Name` 读的就是它。
        //    不回带的话那条永远是 `null`：管理端填没填、回落到什么，审计里都看不出来
        //    （2026-10-12 手验实测到这处：活动行有名字、审计行没有）。
        result.put("Name", created.get("Name"));
        return result;
    }

    // ==================== 活动 ====================

    public Map<String, Object> activities(String status, String merchant, Integer limit) {
        String st = trimToNull(status);
        if (st != null && !ACTIVE.equals(st) && !STOPPED.equals(st)) {
            return fail("Status 只认 active / stopped");
        }
        int effective = normalizeLimit(limit);
        String m = trimToNull(merchant);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", seckillDao.selectActivities(st, m, effective));
        result.put("total", seckillDao.countActivities(st, m));
        result.put("limit", effective);
        return result;
    }

    /**
     * 停用活动（逻辑废弃）。回带**改前快照**供留痕（同 {@code SubsidyService.ruleStop}）。
     * ⚠️ 支持按 {@code ActivityId} 业务串停 —— 用户端拿到的是业务串，管理端列表拿的是行 Id，
     * 两者都要能用（本仓 {@code admin_audit.TargetId} 记的也是业务串）。
     */
    public Map<String, Object> stop(String activityId) {
        String aid = trimToNull(activityId);
        if (aid == null) {
            return fail("缺少参数：ActivityId");
        }
        Map<String, Object> before = seckillDao.selectActivityByActivityId(aid);
        if (before == null) {
            return fail("活动不存在");
        }
        int updated = seckillDao.updateActivityStatus(aid, STOPPED);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", updated == 0 ? "状态未变（已是停用）" : "活动已停用");
        result.put("updated", updated);
        result.put("before", before);
        result.put("ActivityId", aid);
        return result;
    }

    /**
     * 用户端：可展示的成立活动（**进行中 + 未开启**，已结束 / 已停用 / 商品下架一律不回），
     * 每行补一个 {@code SoldOut}。
     *
     * <p>⚠️ {@code Stage} 由 SQL 现算（库时钟），{@code SoldOut} 在这里用**数字**比 ——
     * 不让前端拿 {@code SELECT (a > b)} 那种 {@code 0/1} 去判真伪：驱动回来的 {@code "0"}
     * 在 JS 里是**真值**（非空字符串），那会满屏「已售完」。
     */
    public Map<String, Object> live(Integer limit) {
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = seckillDao.selectLive(effective);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new HashMap<>(row);
            int total = intOf(row.get("TotalStock"));
            int sold = intOf(row.get("SoldStock"));
            copy.put("SoldOut", total > 0 && sold >= total);
            copy.put("SoldLeft", total > 0 ? Math.max(0, total - sold) : null);
            out.add(copy);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", out);
        result.put("total", out.size());
        result.put("limit", effective);
        // 倒计时基准：库时钟（Stage 就是库时钟现算的，前端不能拿本机钟去比 StartAt）。
        try {
            Map<String, Object> now = seckillDao.selectServerNow();
            if (now != null) {
                result.put("serverNow", now.get("ServerNow"));
                result.put("serverTimestamp", now.get("ServerTimestamp"));
            }
        } catch (Exception e) {
            // 读钟失败不影响列表本身；前端缺 serverTimestamp 时退回本机钟并显示「以系统时间为准」。
            logger.warn("读取秒杀页库时钟失败，本轮不返回 serverTimestamp", e);
        }
        return result;
    }

    // ==================== 内部 ====================

    /**
     * 成立一场活动的**唯一出口** —— 两条路径都走这里。
     *
     * <p>⚠️ 落库前查**区间重叠**（同商品同时段只许一条成立秒杀）：MySQL 没有区间唯一约束，
     * 只能「查一次 + 插一次」。两个调用方（{@link #respond} / {@link #review}）都已把这次判定
     * **提前到各自 CAS 之前**（那时还有干净的回退路径）—— 这里留着是**第二道**：
     * 只挡「两次请求恰好挤在同一个瞬间」的竞态。真撞上时留下的是「流程行已成立、活动未落库」，
     * 那比落一场**互相重叠**的秒杀更安全（本域宁可少一场，不可重一场）。
     *
     * <p>⚠️ 活动名：**流程表没有 `Name` 列**（A 组定的表结构，本笔零加列）⇒ 邀请路径只能拿
     * 商品名回落；申请路径由管理端在审核时给（留空同样回落商品名）。
     * 要让「邀请」也带活动名，得给 `seckill_invitation` **加列** —— 那是硬线，不在本笔自裁范围。
     */
    private Map<String, Object> createActivity(String name, String merchant, int goodsId, String goodName,
                                               Double originalPrice, Double seckillPrice, int quantity,
                                               String applyEndAt, String startAt, String endAt,
                                               String sourceKind, String sourceId) {
        if (seckillDao.countOverlapping(goodsId, startAt, endAt) > 0) {
            return fail("该商品在这个时段已有一场秒杀，不能重叠");
        }
        String activityId = newBizId("SKA-");
        String activityName = name == null || name.isBlank() ? goodName : name;
        try {
            seckillDao.insertActivity(activityId, activityName == null ? "" : activityName,
                    merchant == null ? "" : merchant, goodsId, goodName == null ? "" : goodName,
                    originalPrice == null ? 0d : originalPrice,
                    seckillPrice == null ? 0d : seckillPrice, quantity,
                    applyEndAt, startAt, endAt, sourceKind, sourceId);
        } catch (Exception ex) {
            ex.printStackTrace();
            return fail("成立活动失败：" + ex.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("ActivityId", activityId);
        result.put("Name", activityName);
        return result;
    }

    /**
     * 秒杀价判据：{@code > 0} **且** {@code < 原价}。
     *
     * <p>⚠️ 秒杀价就是**成交价**，不与券 / 折扣叠加（`b5-rule-seckill.md` §四）——
     * 故这里只比原价，不比「折扣后的价」。
     */
    private static String checkPrice(Double price, double originalPrice) {
        if (price == null || price <= 0) {
            return "秒杀价必须大于 0";
        }
        if (price >= originalPrice) {
            return "秒杀价必须低于原价 ¥" + round2(originalPrice);
        }
        return null;
    }

    /**
     * 三个时间点的**格式 + 先后**校验。
     *
     * @param applyEndOptional 邀请路径允许「先不给报名截止」（管理员可以只给开始 / 结束）；
     *                         申请路径的审核则必须给全窗口，故那里传 {@code false} 只校验格式与先后
     * @return 出错文案；{@code null} = 通过
     */
    private static String checkWindow(String applyEndAt, String startAt, String endAt, boolean applyEndOptional) {
        String s = trimToNull(startAt);
        String e = trimToNull(endAt);
        String a = trimToNull(applyEndAt);
        if (s != null && !DATETIME.matcher(s).matches()) {
            return "开始时间格式应为 YYYY-MM-DD HH:MM:SS";
        }
        if (e != null && !DATETIME.matcher(e).matches()) {
            return "结束时间格式应为 YYYY-MM-DD HH:MM:SS";
        }
        if (a != null && !DATETIME.matcher(a).matches()) {
            return "报名截止格式应为 YYYY-MM-DD HH:MM:SS";
        }
        if (s != null && e != null && s.compareTo(e) >= 0) {
            return "开始时间必须早于结束时间";
        }
        // 报名截止必须**不晚于**开始（报名期在活动开始之前）—— 反过来就会出现
        // 「活动已在进行、报名却还没截止」这种自相矛盾的行。
        if (a != null && s != null && a.compareTo(s) > 0) {
            return "报名截止不能晚于开始时间";
        }
        if (!applyEndOptional && (s == null || e == null)) {
            return "通过审核必须给出开始与结束时间";
        }
        return null;
    }

    /** 两条路径共用的「已经处理过」回体 —— **不是失败**（同 {@code SubsidyService.confirm}）。 */
    private static Map<String, Object> alreadyHandled(Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("outcome", "already-handled");
        result.put("msg", "该记录已被处理过");
        result.put("Id", id);
        return result;
    }

    private static String normalizeInvitationStatus(String status) {
        String st = trimToNull(status);
        if (st == null) {
            return null;
        }
        return INVITED.equals(st) || ACCEPTED.equals(st) || DECLINED.equals(st) ? st : null;
    }

    private static String normalizeApplicationStatus(String status) {
        String st = trimToNull(status);
        if (st == null) {
            return null;
        }
        return SUBMITTED.equals(st) || APPROVED.equals(st) || REJECTED.equals(st) ? st : null;
    }

    /**
     * 业务号：{@code 前缀 + 4 个大写字母 + 8 位数字}（同 {@code SubsidyService.newRuleId} 的形状）。
     * 前缀形如 {@code "SKI-"} / {@code "SKA-"} —— 与 `b5-rule-seckill.md` §八 那条
     * 「`TargetId` 传活动业务串 `SKI-<Id>` / `SKA-<Id>`」逐字对齐。
     */
    private static String newBizId(String prefix) {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(16).append(prefix);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 4; i++) {
            sb.append(letters.charAt(random.nextInt(letters.length())));
        }
        for (int i = 0; i < 8; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Double parseDouble(String s) {
        return parseDouble(s, null);
    }

    private static Double parseDouble(String s, Double fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double toDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double dblOf(Object o) {
        Double d = toDouble(o);
        return d == null ? 0d : d;
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
