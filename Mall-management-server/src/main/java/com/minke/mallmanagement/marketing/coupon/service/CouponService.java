package com.minke.mallmanagement.marketing.coupon.service;

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
    public static final int DEFAULT_LIMIT = 100;

    /** 列表上限 —— 防止一次拉爆整表。 */
    public static final int MAX_LIMIT = 500;

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

    /** 领券中心：启用中且未过期的券 + 「我是否已领 / 是否已领完」两个布尔。 */
    public Map<String, Object> claimable(String account, Integer limit) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        int effective = normalizeLimit(limit);
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", couponDao.selectClaimable(account.trim(), effective));
            result.put("limit", effective);
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
            int rows = couponDao.tryClaim(couponId);
            if (rows == 0) {
                // 四种原因里最可能的是「过期」或「领完」—— 都念出来，别让用户猜。
                return fail("领取失败：该券已过期或已领完");
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
            return fail("领取失败：" + e.getMessage());
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
            if (STATUS_ACTIVE.equals(target) && isExpired(before)) {
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

    /** {@code EndAt} 已经是过去（读回来的 {@code EndAt} 是 {@code 'YYYY-MM-DD HH:MM:SS'} 文本）。 */
    private static boolean isExpired(Map<String, Object> coupon) {
        Object endAt = coupon.get("EndAt");
        if (endAt == null) {
            return false;
        }
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // 格式是零填充的 ISO 式 ⇒ 字典序比较即时间序（同 OrderPage 对 CreateTime 的口径）。
        return String.valueOf(endAt).compareTo(now) <= 0;
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

    /** {@code null} / 非正数 ⇒ 默认 100；超上限 ⇒ 截到 500（同 {@code AuditService.normalizeLimit}）。 */
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
}
