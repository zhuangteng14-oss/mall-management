package com.minke.mallmanagement.marketing.coupon.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 营销域「优惠券」的服务层（C3 块 5 · commit 1）。**业务规则 + 响应组装**；
 * 路由与入参绑定在两个 Controller（{@code CouponController} 用户端 / {@code AdminCouponController} 管理端），
 * 数据访问全在 {@link CouponDao}。契约见 {@code docs/agent/context-packs/} 与块内规则稿
 * （{@code _work/b5-rule-coupon.md}）。
 *
 * <h2>【三类券的口径（裁定 §一）】</h2>
 * <table>
 *   <tr><td>{@code threshold}</td><td>满减：满 {@code Threshold} 减 {@code FaceValue}</td></tr>
 *   <tr><td>{@code percent}</td><td>折扣：{@code FaceValue} 是**折扣率**（{@code 0.85} = 8.5 折），
 *       取值范围 {@code (0, 1)}；{@code Threshold} 是门槛（可为 0）</td></tr>
 *   <tr><td>{@code none}</td><td>无门槛：直接减 {@code FaceValue}，{@code Threshold} 强制 0</td></tr>
 * </table>
 * ⚠️ 「折扣率还是减免百分比」我方取的是**折扣率**（与建表时 {@code coupon.FaceValue} 的列注释
 * 「折扣=折扣率」逐字一致）。若您要的是「减 N%」（{@code FaceValue ∈ (0,100)}），说一声，
 * 改的只有本类的 {@code validateTypeFields} 与前端那一行提示。
 *
 * <h2>【{@code Account} = **登录名**（2026-10-12 改口径）；领券两道闸】</h2>
 * {@code Account} 与 {@code coupon_grant.ToAccount} 一律是 {@code login.UserName}
 * （= {@code user.username}），不再是 {@code user.customerid}。理由与迁移见
 * {@code CouponDao} 类注释那一节（一句话：商家没有 customerid，两端合一份券包就没法挂）。
 *
 * <p><b>「不能领自己发的券」是两道闸，判据同款：</b>
 * <ol>
 *   <li><b>列表侧</b> —— {@code CouponDao.selectClaimable} 的 {@code NOT_SELF_ISSUED}：
 *       领券中心与一键领取都拿不到它；</li>
 *   <li><b>动作侧</b> —— {@link #doClaim} 里那一段（本类）：真闸，挡构造请求。</li>
 * </ol>
 * ⚠️ 两处判据必须成对改（{@code selfMerchantId == coupon.MerchantAccount}）。**只有一道都不算**：
 * 列表那道管体验，动作那道管边界。
 *
 * <h2>【{@code TotalQty = 0} 解释成「不限量」，不是「一个都不发」】</h2>
 * 这是本表的一处**语义选择**，写在明面上：{@code 0} 若按字面读会得到「一张都领不到」，
 * 那不是任何人建券时想要的。判据在 SQL 里一律写成 {@code (TotalQty = 0 OR ClaimedQty < TotalQty)}
 * —— 三处（{@code tryClaim} / {@code selectClaimable} / {@code selectExpiringUnclaimed}）同款。
 *
 * <h2>【领券 = 「先占名额、撞键补偿」两条语句，不是一笔事务】</h2>
 * 顺序：{@code tryClaim}（CAS 占名额）→ {@code insertGrant}；后者撞唯一键（这个人已经领过）
 * ⇒ {@code releaseClaim} 把名额退回去。⚠️ **反过来（先插行再占名额）会错得更重**：
 * 名额占不上时行已经在库里了，那张券就成了「领得到但用不掉」的幽灵。
 * <p>⚠️ 残余风险如实登记：两步之间进程死掉 ⇒ {@code ClaimedQty} 多算 1（少发一张，不是多发）。
 * 用 {@code @Transactional} 能修掉这一格，但那会让本类在 {@code @WebMvcTest} 切片里多一层
 * no-op 语义（切片无 DataSource ⇒ 无 TransactionManager），而本仓的写路径惯来不用事务回滚
 * （同 {@code CartService.add} / {@code TemplateService}）。取舍：**选后者，并把它写下来**。
 */
@Service
public class CouponService {

    /** 列表默认条数（前端不传 {@code limit} 时）。 */
    public static final int DEFAULT_LIMIT = PageLimits.DEFAULT_PAGE_SIZE;

    /** 列表上限 —— 防止一次拉爆整表。 */
    public static final int MAX_LIMIT = PageLimits.MAX_PAGE_SIZE;

    /** 趋势图回看的自然日数（有界，见 {@code CouponDao.selectClaimDaily} 对「内联整数」的说明）。 */
    public static final int TREND_DAYS = 14;

    private static final String TYPE_THRESHOLD = "threshold";
    private static final String TYPE_PERCENT = "percent";
    private static final String TYPE_NONE = "none";

    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_MERCHANT = "merchant";

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_STOPPED = "stopped";

    /** 「快过期」桶名（与 {@code CouponDao.selectMyGrants} 的 {@code Bucket} 取值**逐字**一致）。 */
    private static final String BUCKET_EXPIRING = "expiring";

    /**
     * 有效期格式闸：{@code 'YYYY-MM-DD HH:MM:SS'}（**必须零填充**）。
     * ⚠️ 不校验就会把 {@code '2026/11/1'} 这种串交给 MySQL —— 它要么按「不完全日期」静默补零，
     * 要么直接报错，两种都不是调用方想要的。
     */
    private static final Pattern DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");

    private final CouponDao couponDao;

    public CouponService(CouponDao couponDao) {
        this.couponDao = couponDao;
    }

    // ==================== 管理端：券定义 ====================

    /** 券列表。{@code Status} 走白名单（未知值**报错**，不静默当成「不筛」—— 同订单域的处置口径）。 */
    public Map<String, Object> list(String status, String keyword, Integer limit) {
        String st = trimToNull(status);
        if (st != null && !STATUS_ACTIVE.equals(st) && !STATUS_STOPPED.equals(st)) {
            return fail("未知的状态：" + status + "（只能是 active / stopped）");
        }
        int effective = normalizeLimit(limit);
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", couponDao.selectCoupons(st, trimToNull(keyword), effective));
            result.put("total", couponDao.countCoupons(st, trimToNull(keyword)));
            result.put("limit", effective);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 建券。**逐条校验全在 {@link #validate} 里**，任一不过就 {@code success:false} ——
     * 不做「先落库再修正」。
     *
     * <p>数字参数收的是**字符串**：{@code @RequestParam Double} 遇到 {@code "abc"} 会抛
     * {@code MethodArgumentTypeMismatchException} → 400 空体，而本仓的口径是
     * 「业务失败 = 200 + success:false + 说得清的话」（同 {@code AdminTemplateController} 的
     * 「参数全 {@code required=false} + 手判」）。故解析也在这里做，错在哪一个字段能点名。
     */
    public Map<String, Object> create(String name, String type, String faceValueRaw, String thresholdRaw,
                                      String totalQtyRaw, String scope, String merchantAccount,
                                      String categoryMatch, String startAt, String endAt) {
        Map<String, Object> bad = validate(name, type, faceValueRaw, thresholdRaw, totalQtyRaw,
                scope, merchantAccount, categoryMatch, startAt, endAt);
        if (bad != null) {
            return bad;
        }
        String t = type.trim().toLowerCase();
        Double faceValue = parseDouble(faceValueRaw);
        Double threshold = TYPE_NONE.equals(t) ? 0d : parseDouble(thresholdRaw, 0d);
        int totalQty = parseTotalQty(totalQtyRaw);
        String sc = scope == null || scope.isBlank() ? SCOPE_ALL : scope.trim().toLowerCase();
        String merchant = merchantAccount == null ? "" : merchantAccount.trim();
        String category = categoryMatch == null ? "" : categoryMatch.trim();
        String couponId = newCouponId();
        try {
            couponDao.insertCoupon(couponId, name.trim(), t, faceValue, threshold, totalQty,
                    sc, merchant, category, trimToNull(startAt), trimToNull(endAt));
        } catch (Exception e) {
            e.printStackTrace();
            return fail("建券失败：" + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "券已创建");
        result.put("CouponId", couponId);
        return result;
    }

    /**
     * **商家券提审**（C3 块 5 · A 组）——「更改商品」里挂的商品券，随商品一起提交审。
     *
     * <p>与 {@link #create} 的三点不同，逐条都有理由：
     * <ol>
     *   <li><b>业务号由提审批次号派生</b>（{@link #pendingCouponId}）—— 零 DDL 的挂接方式：
     *       审核通过时只要拿提审单的 {@code BatchId} 反算出 {@code CouponId}，就知道该翻哪一张券，
     *       <b>不需要在 {@code goods_audit} 上新增任何列</b>。</li>
     *   <li><b>落库即 {@code stopped}</b> —— 领券中心（{@code selectClaimable}）与券包
     *       （{@code selectMyGrants}）都只读 {@code active} ⇒ 待审券在用户侧**整条链路都不可见**。
     *       「待审」这个状态就落在既有的 {@code Status} 上，不新增列。</li>
     *   <li><b>{@code Scope} 恒 {@code merchant}、{@code MerchantAccount} 恒商家自己</b> ——
     *       调用方传不进平台券（这个入口本就是给商家券的）。</li>
     * </ol>
     *
     * <p>⚠️ 与 {@link #create} 共用同一套 {@link #validate} —— 校验口径不分商家 / 管理端。
     *
     * @param batchId 提审批次号（调用方生成；同一个号会写进那条 {@code goods_audit} 行）
     */
    public Map<String, Object> createPendingMerchantCoupon(String batchId, String merchantAccount,
                                                           String name, String type, String faceValueRaw,
                                                           String thresholdRaw, String totalQtyRaw,
                                                           String categoryMatch, String startAt, String endAt) {
        Map<String, Object> bad = validate(name, type, faceValueRaw, thresholdRaw, totalQtyRaw,
                SCOPE_MERCHANT, merchantAccount, categoryMatch, startAt, endAt);
        if (bad != null) {
            return bad;
        }
        String t = type.trim().toLowerCase();
        Double faceValue = parseDouble(faceValueRaw);
        Double threshold = TYPE_NONE.equals(t) ? 0d : parseDouble(thresholdRaw, 0d);
        int totalQty = parseTotalQty(totalQtyRaw);
        String category = categoryMatch == null ? "" : categoryMatch.trim();
        String couponId = pendingCouponId(batchId);
        try {
            couponDao.insertCoupon(couponId, name.trim(), t, faceValue, threshold, totalQty,
                    SCOPE_MERCHANT, merchantAccount == null ? "" : merchantAccount.trim(), category,
                    trimToNull(startAt), trimToNull(endAt), STATUS_STOPPED);
        } catch (Exception e) {
            e.printStackTrace();
            return fail("建券失败：" + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "商品券已提交，待审核");
        result.put("CouponId", couponId);
        result.put("Pending", true);
        return result;
    }

    /**
     * 提审券的业务号 —— **由提审批次号派生**。零 DDL 挂接的另一半（见 {@link #createPendingMerchantCoupon}）：
     * 审核链只凭 {@code goods_audit.BatchId} 就能找回这张待审券。
     *
     * <p>⚠️ 不撞 {@link #newCouponId} 的号段：管理端建券是 {@code CP} + 4 大写字母 + 8 数字（14 位），
     * 这里是 {@code CP} + UUID（38 位）。{@code CouponId} 是 {@code varchar(40)} ⇒ 放得下。
     */
    public static String pendingCouponId(String batchId) {
        return "CP" + batchId;
    }

    /** 停用（**逻辑废弃**，不物理删 —— 规则 v2）。已领的券不受影响，只挡新的领取。 */
    public Map<String, Object> stop(String couponId) {
        return changeStatus(couponId, STATUS_STOPPED, "券已停用");
    }

    /** 重新启用。**拦的是已经过期的券** —— 启用了也领不到，那是一次没有效果的点击。 */
    public Map<String, Object> activate(String couponId) {
        return changeStatus(couponId, STATUS_ACTIVE, "券已启用");
    }

    // ==================== 管理端：领取明细 / 定向发放 / 图表 ====================

    /** 某张券的领取明细。 */
    public Map<String, Object> grants(String couponId, Integer limit) {
        if (isBlank(couponId)) {
            return fail("缺少参数：CouponId");
        }
        int effective = normalizeLimit(limit);
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("row", couponDao.selectCoupon(couponId.trim()));
            result.put("list", couponDao.selectGrantsByCoupon(couponId.trim(), effective));
            result.put("limit", effective);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    /** 定向发放：管理端指定一个人给一张券（{@code Source='grant'}）。与自助领取共用同一套闸。 */
    public Map<String, Object> grant(String couponId, String toAccount) {
        if (isBlank(couponId) || isBlank(toAccount)) {
            return fail("缺少参数：CouponId / ToAccount");
        }
        return doClaim(couponId.trim(), toAccount.trim(), "grant");
    }

    /**
     * 券 tab 的图表数据（三件齐里的「图」）：
     * ① 近 {@link #TREND_DAYS} 天的**领取 / 核销**双线；② 券**类型占比**。
     *
     * <p>两条日线在 Java 里**对齐成同一串日期**（并集补零）—— 直接回两条各自成对的数组，
     * 前端就得自己对齐，而「某一天只有核销没有领取」时两条线的横轴会**错位一格**，
     * 那是最难看出来的一种图表错误。
     */
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> claim = couponDao.selectClaimDaily(TREND_DAYS);
            List<Map<String, Object>> used = couponDao.selectUsedDaily(TREND_DAYS);

            // LinkedHashMap 保序：先按领取的日期排，再把只在核销里出现的日期补进去。
            Map<String, long[]> byDay = new LinkedHashMap<>();
            for (Map<String, Object> row : claim) {
                byDay.computeIfAbsent(str(row.get("Day")), k -> new long[2])[0] = toLong(row.get("Claimed"));
            }
            for (Map<String, Object> row : used) {
                byDay.computeIfAbsent(str(row.get("Day")), k -> new long[2])[1] = toLong(row.get("Used"));
            }
            List<String> days = new ArrayList<>(byDay.keySet());
            days.sort(String::compareTo);
            List<Long> claimed = new ArrayList<>(days.size());
            List<Long> usedSeries = new ArrayList<>(days.size());
            long claimedTotal = 0;
            long usedTotal = 0;
            for (String day : days) {
                long[] pair = byDay.get(day);
                claimed.add(pair[0]);
                usedSeries.add(pair[1]);
                claimedTotal += pair[0];
                usedTotal += pair[1];
            }

            Map<String, Object> trend = new HashMap<>();
            trend.put("days", days);
            trend.put("claimed", claimed);
            trend.put("used", usedSeries);

            Map<String, Object> totals = new HashMap<>();
            totals.put("claimed", claimedTotal);
            totals.put("used", usedTotal);
            totals.put("coupons", couponDao.countCoupons(null, null));

            result.put("success", true);
            result.put("trend", trend);
            result.put("types", couponDao.selectTypeShare());
            result.put("totals", totals);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("统计失败：" + e.getMessage());
        }
    }

    // ==================== 用户端 ====================

    /**
     * 领券中心：启用中且未过期的券 + 「我是否已领 / 是否已领完」两个布尔。
     *
     * @param thisWeek {@code "1"} ⇒ 只回**本周新发布**的（主页领券中心）；其余 / 省略 ⇒ 全部可领
     *                 （用户中心「可领」tab，含旧券）。两者是**同一个查询**的两个筛法（裁定「甲」，
     *                 见 {@code CouponDao.selectClaimable}）⇒ 主页那批天然是「可领」的**子集**。
     *                 ⚠️ 参数是**字符串**：本仓类级不得有 {@code @Validated}，一律 Service 手判
     *                 （同组里的 {@code Status} / {@code Type} 都是这个形态）。
     */
    public Map<String, Object> claimable(String account, Integer limit, String thisWeek) {
        return claimable(account, limit, thisWeek, null);
    }

    /**
     * 同上，另加**商家筛**（C3 块 5 · A 组 · 商品详情页领券区）：{@code merchantAccount} 非空 ⇒
     * 只回该商家的**商家券**，平台券一条不回（口径见 {@link CouponDao#selectClaimable} 的四参版）。
     *
     * <p>⚠️ 详情页与主页**用的是同一个端点**，只是这里多一个可选筛 ⇒ 主页 / 用户中心那两处
     * 不传它、行为一字未变（严格加性）。
     */
    public Map<String, Object> claimable(String account, Integer limit, String thisWeek,
                                         String merchantAccount) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        int effective = normalizeLimit(limit);
        boolean onlyWeek = "1".equals(thisWeek == null ? "" : thisWeek.trim());
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", couponDao.selectClaimable(account.trim(), effective, onlyWeek, merchantAccount));
            result.put("limit", effective);
            // 「本周还剩 N 天」——区块级小字，两种筛法都回（后端算，前端不碰浏览器钟）。
            result.put("daysLeft", couponDao.selectWeekWindow().get("DaysLeft"));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 券包（我的券）。排序与分桶都在 SQL 里（见 {@code CouponDao.selectMyGrants}），
     * 这里只把「有没有快过期的」折成一个**红点判据**回给前端（裁定 #4 ②）。
     *
     * <p>⚠️ 红点判的是 {@code Bucket = 'expiring'} —— 即「**未使用** + 未过期 + 快到期」。
     * 已过期的、已核销的**不该**点亮红点：那两类的用户动作是「算了」，不是「快去用」。
     */
    public Map<String, Object> myCoupons(String account) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        try {
            List<Map<String, Object>> rows = couponDao.selectMyGrants(account.trim());
            long expiring = 0;
            for (Map<String, Object> row : rows) {
                if (BUCKET_EXPIRING.equals(str(row.get("Bucket")))) {
                    expiring++;
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", rows);
            result.put("redDot", expiring > 0);
            result.put("expiringCount", expiring);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    /** 自助领券（{@code Source='claim'}）。 */
    public Map<String, Object> claim(String couponId, String account) {
        if (isBlank(couponId) || isBlank(account)) {
            return fail("缺少参数：CouponId / Account");
        }
        return doClaim(couponId.trim(), account.trim(), "claim");
    }

    /**
     * **一键领取「本周全部」** —— 批量自助领券（用户 2026-10-11 裁定 ②：真做、**不弹确认**，
     * 券「领了就有」无损失 ⇒ 直接领 + 汇总提示）。
     *
     * <h3>【集合由**服务端**定，前端不传 CouponId 列表】</h3>
     * 「本周」是**服务端语义**（{@code CouponDao.THIS_WEEK}）：让前端把列表传上来就等于
     * 把「本周」的定义复制到前端，两处口径迟早走岔（本仓最忌「口径有两处可抄错」）；
     * 服务端在**点击时刻**重算，也天然避开「前端手里是 1 分钟前的旧列表」。这同时堵掉
     * 「构造任意 ID 列表刷这个接口」的面。
     * <p>集合 = **本周新发布 + 启用中 + 未过期 + 我还没领过 + 还没领完 + 不是我发的**；
     * 逐张走 {@link #doClaim}（同一条单张路径、同一套闸，**不另写一份领券逻辑**）。
     * ⚠️ 「不是我发的」那一条来自 {@code selectClaimable} 的 {@code NOT_SELF_ISSUED}（列表侧）；
     * {@link #doClaim} 里还有同款的第二道（动作侧）—— 两道都要在，见类注释。
     *
     * <h3>【⚠️ 部分失败**不**回 {@code success:false}】</h3>
     * 与 {@code NotificationBroadcastService.send} 类注释那段「刻意不因部分失败而回 false」同源：
     * <ol>
     *   <li>全仓 {@code success:false} 的语义是「这个**请求**没用 / 调用方该改参数或重试」——
     *       部分失败**不是**一个可重试的请求错误；</li>
     *   <li>回 false 会**诱导前端重发整批** ⇒ 已成功的那几张被再点一遍，用户看到一堆假报错，
     *       还白跑一轮「占名额 / 退名额」；</li>
     *   <li>每张的成败已经在 {@code items[].success} 逐条如实回报了。顶层 {@code success:true}
     *       说的是「批量动作**执行完毕**」，与「全成功」是两件事。</li>
     * </ol>
     * ⚠️ 与 {@code CartService.partialFailure} 的方向**相反是有意的**：结算是 **fail-fast**
     * （前几笔真成了订单，必须让用户停下），批量领券是**尽力而为**（一张失败不影响另一张）。
     *
     * <p>逐张**不中断**是天然的：{@link #doClaim} 内部 {@code catch (Exception)} 兜住一切、
     * 回一个 Map、**从不抛异常**（本类刻意零事务，领券走「先占名额、撞键补偿」两条语句）。
     */
    public Map<String, Object> claimBatch(String account) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        String acct = account.trim();
        try {
            List<Map<String, Object>> rows = couponDao.selectClaimable(acct, MAX_LIMIT, true);
            List<Map<String, Object>> items = new ArrayList<>();
            int claimed = 0;
            for (Map<String, Object> row : rows) {
                // 已领 / 已领完的不进集合（「一键领取」只该领**还能领的**）。
                // ⚠️ 这只减少无谓调用，**不是判据** —— 真判据在 doClaim 里 tryClaim 的 CAS 返回行数。
                if (flag(row.get("Claimed")) || flag(row.get("SoldOut"))) {
                    continue;
                }
                String couponId = str(row.get("CouponId"));
                Map<String, Object> one = doClaim(couponId, acct, "claim");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("CouponId", couponId);
                item.put("Name", str(row.get("Name")));
                item.put("success", flag(one.get("success")));
                item.put("msg", str(one.get("msg")));
                items.add(item);
                if (flag(one.get("success"))) {
                    claimed++;
                }
            }
            int failed = items.size() - claimed;
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);   // ←「批量动作执行完毕」，**不是**「全成功」（见上面那节）
            result.put("total", items.size());
            result.put("claimed", claimed);
            result.put("failed", failed);
            result.put("items", items);
            result.put("daysLeft", couponDao.selectWeekWindow().get("DaysLeft"));
            result.put("msg", items.isEmpty()
                    ? "本周没有可领的新券"
                    : "已领取 " + claimed + " 张" + (failed > 0 ? "，" + failed + " 张失败" : ""));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("批量领取失败：" + e.getMessage());
        }
    }

    // ==================== 结算用券（核销，C3 块 5 · commit 5）====================

    /**
     * 结算用券的结论。{@code ok=false} ⇒ 调用方**不得**建单（{@code discount} 恒 0，没有意义）。
     *
     * <p>为什么回一个 record 而不是 {@code {success, msg}} 那个 Map：调用方是
     * {@code OrderService.addLocked}，它要的是「能不能用 + 减多少钱」这一对值，
     * 不是一个准备直接 JSON 出去的响应体 —— 响应体的措辞归 {@code orderAdd} 自己。
     */
    public record Redeem(boolean ok, String msg, double discount) {

        static Redeem yes(double discount) {
            return new Redeem(true, "", discount);
        }

        static Redeem no(String msg) {
            return new Redeem(false, msg, 0d);
        }
    }

    /**
     * **核销一张券并算出减免额**（{@code coupon_grant unused → used}）。结算那道闸的唯一实现。
     *
     * <p>顺序：**先把能拒的都拒掉（纯读），最后才 CAS 核销** —— 与 {@link #doClaim} 同一条纪律：
     * CAS 是唯一的判据，前面那几读只为「把话说清楚」；反过来先核销再校验就会要求补偿腿。
     *
     * <p>⚠️ <b>「是不是我的券」判在 {@code account} 上</b>：{@code coupon_grant} 的键是
     * {@code (CouponId, ToAccount)} 且 {@code ToAccount} 是**登录名** ⇒ 别人拿我的券号来核销，
     * {@code selectGrant} 直接查不到（回「这张券不在你的券包里」），**不是**先查到再比一次。
     *
     * <p>⚠️ <b>时刻判据一律走 {@link CouponDao#selectClaimBlock}（库内 {@code NOW()}）</b>，
     * 不拿 JVM 的钟比 —— 与 {@link #isExpired} 那条 2026-10-11 的教训同源（库时钟 UTC、
     * 开发机东八区，两个钟差 8 小时，会把「还有 8 小时有效」说成已过期，而领券闸说还能领）。
     *
     * <p>⚠️ <b>不判「适用商家」</b>：`Scope='merchant'` 的限制维度是 `CategoryMatch`（商品种类），
     * 而 `MerchantAccount` 是**发券那家商家的业务号**（`''` = 平台券）—— 拿它当放行条件会把
     * 平台券与别家券全拒掉。理由与证据写在方法体里那段注释（`mall_management_sql.sql:1256`
     * ＋ `CouponDao:128`）。按 `CategoryMatch` 判的规则稿里没有 ⇒ 列为**待裁项**，本笔不判。
     *
     * <p>⚠️ <b>【折扣商品不叠满减 / 折扣券】（2026-10-12 用户 A 档批准）</b>：本单含**任意一件**
     * 折扣商品（{@code goods.OriginalPrice > 0}）时，{@code threshold}（满减）与 {@code percent}
     * （折扣）两类券**整单拒**；{@code none}（无门槛）放行。判据逐字来自用户原话 ——
     * 「折扣商品：✅ 可以用**无门槛券**；❌ 不能用**满减券**、**折扣券**」。
     * <ul>
     *   <li><b>为什么整单拒而不是「只对那件商品不折」</b>：券抵的是**本单合计**，部分应用要么得
     *       重新定义「哪些行参与门槛」、要么得按行分摊减免额 —— 两条规则稿里都没有。用户原话是
     *       「若有折扣商品 + 满减 / 折扣券 → 拒」，故只做**整单**这一档。</li>
     *   <li><b>为什么不在这里查</b>：本类只认 {@code coupon} / {@code coupon_grant} 两张表，
     *       「本单有没有折扣商品」要读 {@code order_item} 的码再回 {@code goods} / {@code goods_sku}
     *       —— 那是 {@code OrderDao.selectDiscountCodes} 的活（多型号商品的码是
     *       {@code goods_sku.SkuCode}，须经 {@code GoodsId} 回联父商品取划线原价）。
     *       查的结论由调用方**当参数**传进来（{@code hasDiscountItem}），本类不反向依赖订单域。</li>
     *   <li><b>排在门槛判断之前</b>：这一条是「这张券根本不能用于这一单」，比「本单金额未满」更靠前
     *       —— 一单里两样都不满足时，先说「不可用」才是用户能动作的那句（凑单凑到门槛之上也还是
     *       不能用）。</li>
     * </ul>
     *
     * @param account    领券人**登录名**（会话业务号要先过 {@code CouponDao.selectUsernameByCustomerId}）
     * @param couponId   券的业务串
     * @param merchantId 本单商家**业务号** —— ⚠️ **当前未参与判定**，留着是为了
     *                   「按 `CategoryMatch` 判」那条待裁项落地时不必再改签名
     * @param orderTotal 本单**原价合计**（门槛比它、折扣按它算）
     * @param hasDiscountItem 本单**含不含折扣商品**（{@code goods.OriginalPrice > 0} 的任一件）。
     *                   ⚠️ 调用方（{@code OrderService.addLocked}）**必须先查准**：本方法只拿它做
     *                   一条纯判定，不会自己去查 —— 本类不认识 {@code order_item} / {@code goods_sku}，
     *                   那是订单域与商品域的取数。判据与拒因见下方「折扣商品」那段。
     * @param orderId    本单业务串 —— 核销时写进 {@code coupon_grant.OrderId}，也是
     *                   {@link #release} 认「是不是本单那一次核销」的钥匙
     */
    public Redeem redeem(String account, String couponId, String merchantId, double orderTotal,
                         boolean hasDiscountItem, String orderId) {
        if (isBlank(account)) {
            // 会话里翻不出登录名（`user` 表没这个客户号 / 闸关的切片）⇒ 说不清是谁的券，不放行。
            return Redeem.no("无法识别领券账户");
        }
        Map<String, Object> grant = couponDao.selectGrant(couponId, account);
        if (grant == null) {
            return Redeem.no("这张券不在你的券包里");
        }
        if (!"unused".equals(str(grant.get("Status")))) {
            return Redeem.no("该券已被使用");
        }
        Map<String, Object> coupon = couponDao.selectCoupon(couponId);
        if (coupon == null) {
            return Redeem.no("券不存在");
        }
        if (!STATUS_ACTIVE.equals(str(coupon.get("Status")))) {
            return Redeem.no("该券已停用");
        }
        Map<String, Object> block = couponDao.selectClaimBlock(couponId);
        if (block != null && flag(block.get("NotStarted"))) {
            return Redeem.no("该券还未开始");
        }
        if (block != null && flag(block.get("Expired"))) {
            return Redeem.no("该券已过期");
        }
        // ⚠️ **刻意不判「适用商家」**（2026-10-12 落地当天改回，见文末 javadoc 末段）——
        //    `Scope='merchant'` 的**限制维度是 `CategoryMatch`（商品种类）不是商家**：
        //    建表注释 `mall_management_sql.sql:1256` 逐字写着「适用档：all 全场 / merchant
        //    部分商家（**限商品种类**）」；`CouponDao` 类注释 `:128` 又把
        //    `MerchantAccount IS NULL / = ''` 定义为**平台券**、非空是**发券那家商家的业务号**。
        //    ⇒ 拿 `MerchantAccount` 是本单 `merchantId` 当放行条件，会把**每一张平台券**
        //    和**每一张别家发的券**全拒掉（实测：券包里那张 `Scope=merchant` /
        //    `MerchantAccount=''` / `CategoryMatch='生活用品,服装,美食'` 会被误判成「不适用于本店」）。
        //    按 `CategoryMatch` 判需要本单的商品种类，而订单在 `addLocked` 那一步只有
        //    `ProductCode`（种类要回查 `goods`）＋「一单混类目时算不算适用」这条规则**稿里没有**
        //    ⇒ 不在这里自造规则，**列为待裁项**（见 commit 5 提交信息「待裁」段）。
        String type = str(coupon.get("Type"));
        // ⚠️ 折扣商品闸 —— 见方法 javadoc 那段；`none`（无门槛）放行，另两类整单拒。
        if (hasDiscountItem && (TYPE_THRESHOLD.equals(type) || TYPE_PERCENT.equals(type))) {
            return Redeem.no("本单含折扣商品，满减 / 折扣券不可用");
        }
        double face = decimal(coupon.get("FaceValue"));
        double threshold = decimal(coupon.get("Threshold"));
        if (TYPE_THRESHOLD.equals(type) && orderTotal < threshold) {
            return Redeem.no("本单金额未满 ¥" + money(threshold) + "，该券不适用");
        }
        // `percent` 的 `FaceValue` 是**折扣率**（0.85 = 8.5 折，见 `coupon-format.js` 的 `couponFace`）
        // ⇒ 减的是 `总额 × (1 - 率)`；另两类 `FaceValue` 就是**减免金额**（`none` 型后端强制 Threshold=0）。
        double discount = TYPE_PERCENT.equals(type) ? orderTotal * (1d - face) : face;
        // 上限锁死在**本单金额**：超出部分是白送（`none` 券的面值可能大于本单），下限锁 0
        // （`percent` 的 `FaceValue` 若被配成 >1，`1 - face` 会是负数 —— 那会**加价**）。
        discount = Math.max(0d, Math.min(discount, orderTotal));
        discount = Math.round(discount * 100d) / 100d;
        // 唯一的判据：CAS。返回 0 ⇒ 两次结算同时用了同一张券，这一次没抢到。
        if (couponDao.markUsed(couponId, account, orderId) == 0) {
            return Redeem.no("该券已被使用");
        }
        return Redeem.yes(discount);
    }

    /**
     * 业务号（{@code customerid}）→ 登录名 —— 券包的键。查无此人 ⇒ {@code null}。
     *
     * <p>结算侧的入口是 {@code orderAdd}（手里只有会话里的业务号），而
     * {@code coupon_grant.ToAccount} 存的是**登录名** ⇒ 中间必须翻这一次。
     * 查不到**不编身份**：返回 {@code null} 让 {@link #redeem} 说出「无法识别领券账户」。
     */
    public String usernameOf(String customerId) {
        if (isBlank(customerId)) {
            return null;
        }
        return couponDao.selectUsernameByCustomerId(customerId.trim());
    }

    /**
     * {@link #redeem} 的**补偿腿**：券已经核销、订单却没建成 ⇒ 把这张券放回未使用。
     *
     * <p>⚠️ 只回退**本单**那一次（{@code OrderId} 钥匙，见 {@code CouponDao.releaseUsed} 的
     * {@code WHERE} 说明）—— 按 {@code (CouponId, ToAccount)} 无脑回退会掀掉别人那次真实核销。
     * <p>⚠️ 调用方（{@code OrderService.addLocked}）**fail-soft**：回退失败只记一行日志，
     * 不改变「订单没建成」这个结论（那时用户已经拿到失败文案，再抛异常只会盖掉它）。
     */
    public boolean release(String account, String couponId, String orderId) {
        if (isBlank(account) || isBlank(couponId) || isBlank(orderId)) {
            return false;
        }
        return couponDao.releaseUsed(couponId, account, orderId) > 0;
    }

    // ==================== 内部 ====================

    /**
     * 领券/发放的**唯一实现**（两条入口只差一个 {@code Source}）。
     *
     * <p>步骤：先**读一次券**给出说得清的话术（不存在 / 已停用 / 已过期 / 已领完），再
     * {@code tryClaim} 原子占名额，最后写领取行；撞唯一键 ⇒ 退还名额 + 「您已领取过该券」。
     *
     * <p>⚠️ 先读那一次**不是**判据（读完到占名额之间别人可能把它领光）—— 判据只有
     * {@code tryClaim} 的返回行数。先读纯粹是为了**把错误说清楚**。
     */
    private Map<String, Object> doClaim(String couponId, String account, String source) {
        try {
            Map<String, Object> coupon = couponDao.selectCoupon(couponId);
            if (coupon == null) {
                return fail("券不存在");
            }
            if (STATUS_STOPPED.equals(str(coupon.get("Status")))) {
                return fail("该券已停止发放");
            }
            // ⚠️ 「不能领自己发的券」（用户 2026-10-12 裁定「甲」）——**必须排在 tryClaim 之前**：
            //    占名额是一条 UPDATE，先占了再拒就得再补一条 releaseClaim，而那正是「补偿腿只在
            //    撞唯一键时跑」这条既有设计的**第二种补偿路径**，白多一处能忘。
            // ⚠️ 判据与 `CouponDao.NOT_SELF_ISSUED`（列表侧那道筛）**同款**：发券商家的业务号
            //    == 这个人自己的 merchantid。两处必须成对改 —— 列表漏筛只是白点一下，
            //    列表筛了服务端没筛才是真漏（本闸就是防那一半）。
            // ⚠️ 未登录 / 客户 / 管理员问不出 merchantid（回 null）⇒ **不拦**：他们谈不上
            //    「自己发的券」（客户与管理员根本没有 merchantid，`user` 表实测全为 NULL）。
            String selfMerchantId = couponDao.selectMerchantIdByUsername(account);
            String issuer = str(coupon.get("MerchantAccount"));
            if (selfMerchantId != null && !selfMerchantId.isEmpty() && selfMerchantId.equals(issuer)) {
                return fail("不能领自己发的券");
            }
            int rows = couponDao.tryClaim(couponId);
            if (rows == 0) {
                // ⚠️ 这里**不再**合并成一句「该券已过期或已领完」了（用户 2026-10-11 手验要求拆开）。
                //    那条话术的两个毛病：①「已过期」与「已领完」是两个不同的下一步（一个要另建券、
                //    一个要等/加量），合并等于什么都没说；②它**漏了第三种原因「还没开始」**——
                //    用户那次撞的就是这一种，却被指着去查「是不是过期了」，白查一轮。
                //    真因按**库时钟**逐条判（`selectClaimBlock`，表达式与 `tryClaim` 的 WHERE 同款）。
                return fail(claimBlockReason(couponId));
            }
            try {
                couponDao.insertGrant(couponId, account, source);
            } catch (DuplicateKeyException e) {
                // 每人每券限 1 张（uk_coupon_grant_coupon_acct）⇒ 这不是异常路径，是一条正常的路。
                couponDao.releaseClaim(couponId);
                return fail("您已领取过该券");
            }
        } catch (Exception e) {
            e.printStackTrace();
            // ⚠️ 消息里**不带**「领取失败：」前缀：两个调用方（首页券包 / PDP 领券行）都会自己拼
            //    `'领取失败：' + msg`，再带一层就成了「领取失败：领取失败：…」（用户 2026-10-11 手验）。
            //    前缀归前端、原因归后端 —— 这条分工也适用于下面 `claimBlockReason` 的返回值。
            return fail(e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "grant".equals(source) ? "已发放" : "领取成功");
        result.put("CouponId", couponId);
        return result;
    }

    /** 改状态的共用腿（停用 / 启用只差一个目标状态）。回带 {@code before} 供审计并排 diff。 */
    private Map<String, Object> changeStatus(String couponId, String target, String okMsg) {
        if (isBlank(couponId)) {
            return fail("缺少参数：CouponId");
        }
        String id = couponId.trim();
        try {
            Map<String, Object> before = couponDao.selectCoupon(id);
            if (before == null) {
                return fail("券不存在");
            }
            if (STATUS_ACTIVE.equals(target) && isExpired(id)) {
                return fail("该券已过有效期，启用也不会有人能领 —— 请改有效期或另建一张");
            }
            int rows = couponDao.updateStatus(id, target);
            Map<String, Object> result = new HashMap<>();
            if (rows == 0) {
                result.put("success", false);
                result.put("msg", STATUS_ACTIVE.equals(target) ? "券已经是启用状态" : "券已经是停用状态");
                return result;
            }
            result.put("success", true);
            result.put("msg", okMsg);
            result.put("before", before);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("操作失败：" + e.getMessage());
        }
    }

    /**
     * {@link CouponDao#tryClaim} 返回 0 时，念一句**指得准**的原因。
     *
     * <p>三个标志**互相独立**（可以同时成立，例如一张既没开始又已领完的券）⇒ 按「用户最先该
     * 处理哪一件事」排优先级：还没开始 &gt; 已过期 &gt; 已领完。都判不出来时回一句**如实**的兜底，
     * 不编一个像模像样的原因（那正是本次要拆掉的那种话术）。
     *
     * <p>⚠️ 返回的是**光秃秃的原因**，不带「领取失败：」前缀 —— 两个前端调用点都自己拼前缀。
     */
    private String claimBlockReason(String couponId) {
        Map<String, Object> block = couponDao.selectClaimBlock(couponId);
        if (block == null) {
            return "券不存在";
        }
        if (flag(block.get("NotStarted"))) {
            return "该券还未开始";
        }
        if (flag(block.get("Expired"))) {
            return "该券已过期";
        }
        if (flag(block.get("SoldOut"))) {
            return "该券已领完";
        }
        // 三条都没命中却也没占上名额：只可能是「读完之后状态被改了」这类竞态 —— 如实说，别猜。
        return "领取未成功（名额未占上，请稍后重试）";
    }

    /**
     * 该券是不是**已经过期** —— 判据走**库时钟**（{@code NOW()}），与领券闸同一个钟。
     *
     * <p>⚠️ 2026-10-11 改：这里**曾经**是拿 {@code LocalDateTime.now()}（**JVM** 时钟）与读回来的
     * {@code EndAt} 文本比字典序。在库时钟 = UTC、开发机时钟 = 东八区的环境里，那两个钟差 8 小时 ⇒
     * 「启用」会在券**还有 8 小时**有效期时就拒掉它，而领券闸（走 {@code NOW()}）却认为还能领 ——
     * 同一张券两个钟给两个答案。根因与用户 2026-10-11 手验报的那一条同族（**双时钟**），故一并收掉。
     * 现在只认库时钟，全类不再有第二个「现在几点了」。
     */
    private boolean isExpired(String couponId) {
        Map<String, Object> block = couponDao.selectClaimBlock(couponId);
        return block != null && flag(block.get("Expired"));
    }

    /**
     * 回读的「是/否」标志 —— 宽容判据。
     *
     * <p>⚠️ 同一个字段可能是 {@code Boolean.TRUE} 也可能是 {@code 1}/{@code "1"}（Connector/J 的
     * {@code tinyInt1isBit} 与驱动版本相关）⇒ 别用 {@code Boolean.TRUE.equals(...)}，那会把
     * 「其实是 1」判成否，**静默**给出错误结论（本仓 `AccountsPage.isBanned` / `notify-events.js`
     * 都有同一句宽容写法）。
     */
    private static boolean flag(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /**
     * 建券的全量校验。可接受 ⇒ {@code null}；否则回一个 {@code success:false} 的响应。
     * ⚠️ 顺序刻意从「最可能填错的」到「最细的」：先券名与类型，再该类型专属的数字关系，
     * 最后是范围/时间 —— 一次只报**第一条**不合规的，让用户改完再看到下一条。
     */
    private static Map<String, Object> validate(String name, String type, String faceValueRaw,
                                                String thresholdRaw, String totalQtyRaw, String scope,
                                                String merchantAccount, String categoryMatch,
                                                String startAt, String endAt) {
        if (isBlank(name)) {
            return fail("缺少参数：Name（券名）");
        }
        if (isBlank(type)) {
            return fail("缺少参数：Type（threshold / percent / none）");
        }
        String t = type.trim().toLowerCase();
        if (!TYPE_THRESHOLD.equals(t) && !TYPE_PERCENT.equals(t) && !TYPE_NONE.equals(t)) {
            return fail("未知的券类型：" + type + "（只能是 threshold / percent / none）");
        }
        Double faceValue = parseDouble(faceValueRaw);
        if (faceValue == null || faceValue <= 0) {
            return fail("FaceValue 必须是正数");
        }
        if (TYPE_PERCENT.equals(t) && faceValue >= 1) {
            return fail("折扣券的 FaceValue 是折扣率，必须小于 1（0.85 = 8.5 折）");
        }
        Double threshold = parseDouble(thresholdRaw, 0d);
        if (threshold == null || threshold < 0) {
            return fail("Threshold 不能是负数");
        }
        if (TYPE_THRESHOLD.equals(t) && threshold <= 0) {
            return fail("满减券必须给 Threshold（门槛金额 > 0）");
        }
        if (!isBlank(totalQtyRaw)) {
            Integer totalQty = parseInt(totalQtyRaw);
            if (totalQty == null || totalQty < 0) {
                return fail("TotalQty 必须是不小于 0 的整数（0 = 不限量）");
            }
        }
        String sc = isBlank(scope) ? SCOPE_ALL : scope.trim().toLowerCase();
        if (!SCOPE_ALL.equals(sc) && !SCOPE_MERCHANT.equals(sc)) {
            return fail("未知的适用范围：" + scope + "（只能是 all / merchant）");
        }
        if (SCOPE_MERCHANT.equals(sc) && isBlank(categoryMatch)) {
            return fail("适用范围选了「部分商家」就必须给 CategoryMatch（限商品种类，逗号分隔）");
        }
        for (String[] pair : new String[][]{{"StartAt", startAt}, {"EndAt", endAt}}) {
            if (!isBlank(pair[1]) && !DATETIME.matcher(pair[1].trim()).matches()) {
                return fail(pair[0] + " 必须是 YYYY-MM-DD HH:MM:SS（须零填充），收到：" + pair[1]);
            }
        }
        if (!isBlank(startAt) && !isBlank(endAt) && endAt.trim().compareTo(startAt.trim()) <= 0) {
            return fail("EndAt 必须晚于 StartAt");
        }
        return null;
    }

    /** 券业务号：{@code CP} + 4 个大写字母 + 8 位数字（留痕 {@code TargetId} 用）。同 {@code CartService.newOrderId} 的形状。 */
    private static String newCouponId() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(14).append("CP");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 4; i++) {
            sb.append(letters.charAt(random.nextInt(letters.length())));
        }
        for (int i = 0; i < 8; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /** {@code null} / 非正数 ⇒ 默认 100；超上限 ⇒ 截到 100（同 {@code AuditService.normalizeLimit}；D3-3 统一上限）。 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseTotalQty(String s) {
        Integer v = isBlank(s) ? null : parseInt(s);
        return v == null ? 0 : v;
    }

    private static Double parseDouble(String s) {
        return parseDouble(s, null);
    }

    private static Double parseDouble(String s, Double fallback) {
        if (isBlank(s)) {
            return fallback;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    /** {@code decimal(10,2)} 列 → {@code double}（JDBC 可能给 {@code BigDecimal}）。取不到 ⇒ 0。 */
    private static double decimal(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    /** 金额说人话：整数不带 {@code .0}（「本单金额未满 ¥100」而不是「¥100.0」）。 */
    private static String money(double v) {
        String s = String.valueOf(Math.round(v * 100d) / 100d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
