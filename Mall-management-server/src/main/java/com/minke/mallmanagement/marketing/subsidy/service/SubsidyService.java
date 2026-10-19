package com.minke.mallmanagement.marketing.subsidy.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.marketing.subsidy.dao.SubsidyDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 国补（国家补贴）业务服务（C3 块 5 · commit 3）—— 双向制：**平台提名 → 商家确认**。
 *
 * <h2>【一句话说清本域的形状】</h2>
 * 管理端按**类目**建一条规则（补率 + 单笔上限 + 有效期），然后把具体**商品**提名到该规则下；
 * 提名落 {@code subsidy_goods(pending)} 并发一条系统通知给**商家**；商家在消息里
 * 同意 / 拒绝 ⇒ 状态翻 {@code approved} / {@code rejected}，同时回一条通知到**管理端共享收件箱**。
 * 只有 {@code approved} 的商品才会在用户端带上「国家补贴」标签。
 *
 * <h2>【补贴额读侧现算 —— 库里没有金额列】</h2>
 * {@code 补贴额 = min(现价 × Ratio, Cap)}，其中 {@code Cap = 0} 表示**不封顶**（不是「补 0」）。
 * 四舍五入到分。{@code 到手价 = 现价 − 补贴额}。
 * <p>⚠️ 这条口径与折扣那边**刻意相反**：折扣把折算结果写进 {@code goods.OriginalPrice}
 * （商家填的原价是**事实**，不能事后被配置改掉）；国补的补率是**平台随时可调的**，
 * 故按现算 —— 改比例立刻生效，不需要回填任何存量行。
 *
 * <h2>【三条状态机的判据（都在这里，不在 Controller）】</h2>
 * <ol>
 *   <li><b>提名</b>：规则必须在**期内且 active**；商品必须**存在未删**；
 *       同一 (规则, 商品) 只许提名一次 —— 撞唯一键翻成一句人话，不是 500。</li>
 *   <li><b>确认</b>：CAS（{@code Id + MerchantId + Status='pending'}）。
 *       第二次点回 {@code already-handled}（不是失败、也不许再改状态）。</li>
 *   <li><b>拒绝</b>：理由**必填**（同块 3 驳回商品那条口径）—— 缺理由时**一条 SQL 都不发**。</li>
 * </ol>
 *
 * <h2>【⚠️ 本类不写通知】</h2>
 * 通知由 **Controller** 编排（同 {@code GoodsController} / {@code AdminAccountsController}
 * 的既有分工：旁路副作用归 Controller）。理由与 {@code NotificationService} 的 fail-soft 同源
 * ——通知失败不该把已经成功的提名翻成失败，而 Controller 恰好是「业务已成、再发通知」的位置。
 * 故本类的返回体里**带上收件人要用的那几个字段**（{@code MerchantId} / {@code ToAccount} /
 * {@code GoodName}），Controller 直接读，不必回头再查一遍库。
 */
@Service
public class SubsidyService {

    public static final int DEFAULT_LIMIT = PageLimits.DEFAULT_PAGE_SIZE;

    public static final int MAX_LIMIT = PageLimits.MAX_PAGE_SIZE;

    /** {@code subsidy_goods.Status} 取值域 —— 与建表注释逐字一致。 */
    public static final String STATUS_PENDING = "pending";

    public static final String STATUS_APPROVED = "approved";

    public static final String STATUS_REJECTED = "rejected";

    /** {@code subsidy_rule.Status} 取值域。 */
    public static final String RULE_ACTIVE = "active";

    public static final String RULE_STOPPED = "stopped";

    /** 补率上界：100%（0.9999 也放行 —— 建表是 {@code decimal(6,4)}，四位小数）。 */
    private static final double MAX_RATIO = 1.0;

    /** {@code datetime} 列的入参形状（{@code 'YYYY-MM-DD HH:MM:SS'} 或 {@code 'YYYY-MM-DD'}）。 */
    private static final Pattern DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?$");

    private final SubsidyDao subsidyDao;

    public SubsidyService(SubsidyDao subsidyDao) {
        this.subsidyDao = subsidyDao;
    }

    // ==================== 规则 ====================

    /** 规则列表。{@code status} 未知值 ⇒ 报错（**不**静默当成「不筛」，同 {@code CouponService.list}）。 */
    public Map<String, Object> rules(String status, Integer limit) {
        String st = trimToNull(status);
        if (st != null && !RULE_ACTIVE.equals(st) && !RULE_STOPPED.equals(st)) {
            return fail("Status 只认 active / stopped");
        }
        int effective = normalizeLimit(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", subsidyDao.selectRules(st, effective));
        result.put("total", subsidyDao.countRules(st));
        result.put("limit", effective);
        return result;
    }

    /**
     * 建规则。
     *
     * @param ratioRaw 补率，既收 {@code "0.15"} 也收 {@code "15"}（后者按**百分数**读成 0.15）
     *                 —— 管理端页面上写「15%」比写「0.15」自然，两种都认比强迫一种好。
     */
    public Map<String, Object> ruleCreate(String name, String categoryMatch, String ratioRaw,
                                          String capRaw, String startAt, String endAt) {
        if (isBlank(name)) {
            return fail("规则名不能为空");
        }
        Double ratio = parseRatio(ratioRaw);
        if (ratio == null || ratio <= 0 || ratio > MAX_RATIO) {
            return fail("补贴比例必须在 0 与 100% 之间");
        }
        Double cap = parseDouble(capRaw, 0d);
        if (cap == null || cap < 0) {
            return fail("单笔上限不能是负数（0 = 不封顶）");
        }
        String s = trimToNull(startAt);
        String e = trimToNull(endAt);
        if ((s != null && !DATETIME.matcher(s).matches()) || (e != null && !DATETIME.matcher(e).matches())) {
            return fail("生效时间格式应为 YYYY-MM-DD HH:MM:SS");
        }
        if (s != null && e != null && s.compareTo(e) > 0) {
            return fail("生效起不能晚于生效止");
        }
        String ruleId = newRuleId();
        try {
            subsidyDao.insertRule(ruleId, name.trim(),
                    categoryMatch == null ? "" : categoryMatch.trim(), ratio, cap, s, e);
        } catch (Exception ex) {
            ex.printStackTrace();
            return fail("建规则失败：" + ex.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "规则已创建");
        result.put("RuleId", ruleId);
        return result;
    }

    /** 停用（逻辑废弃）。回带**改前快照**供留痕 —— 与 {@code CouponService.stop} 同款。 */
    public Map<String, Object> ruleStop(String ruleId) {
        return changeRuleStatus(ruleId, RULE_STOPPED, "规则已停用");
    }

    /** 重新启用。 */
    public Map<String, Object> ruleActivate(String ruleId) {
        return changeRuleStatus(ruleId, RULE_ACTIVE, "规则已启用");
    }

    // ==================== 提名 / 确认 ====================

    /**
     * 平台提名一件商品到某条规则下。
     *
     * <p>成功回体带 {@code MerchantId} / {@code GoodName} / {@code GoodsId} —— Controller 据此
     * 给**商家**发通知（{@code ToType='merchant'}、{@code ToId=MerchantId}）。
     */
    public Map<String, Object> nominate(String ruleId, Integer goodsId) {
        if (isBlank(ruleId) || goodsId == null || goodsId <= 0) {
            return fail("缺少参数：RuleId / GoodsId");
        }
        Map<String, Object> rule = subsidyDao.selectRule(ruleId);
        if (rule == null) {
            return fail("规则不存在");
        }
        if (!RULE_ACTIVE.equals(str(rule.get("Status")))) {
            return fail("规则已停用，不能提名");
        }
        Map<String, Object> goods = subsidyDao.selectGoodsById(goodsId);
        if (goods == null) {
            return fail("商品不存在或已删除");
        }
        String merchantId = str(goods.get("MerchantId"));
        String goodName = str(goods.get("GoodName"));
        int nominationId;
        try {
            nominationId = subsidyDao.insertNomination(ruleId, goodsId, goodName == null ? "" : goodName,
                    merchantId == null ? "" : merchantId);
        } catch (DuplicateKeyException e) {
            // 唯一键 (RuleId, GoodsId) 撞了 —— 这是**业务事实**（已提名过），不是技术异常。
            return fail("该商品已在这条规则下，无需重复提名");
        } catch (Exception e) {
            e.printStackTrace();
            return fail("提名失败：" + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已提名，等待商家确认");
        // ⚠️ Id = **subsidy_goods 那一行的主键** —— Controller 把它写进通知的 TargetId，
        //    商家的「同意 / 拒绝」按钮才有东西可打（见 SubsidyDao.insertNomination 的 ⚠️）。
        result.put("Id", nominationId);
        result.put("GoodsId", goodsId);
        result.put("GoodName", goodName);
        result.put("MerchantId", merchantId);
        result.put("RuleName", rule.get("Name"));
        return result;
    }

    /** 管理端：提名列表（全部商家的）。 */
    public Map<String, Object> nominations(String status, String merchantId, Integer limit) {
        String st = normalizeNominationStatus(status);
        if (st == null && trimToNull(status) != null) {
            return fail("Status 只认 pending / approved / rejected");
        }
        int effective = normalizeLimit(limit);
        String m = trimToNull(merchantId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", subsidyDao.selectNominations(st, m, effective));
        result.put("total", subsidyDao.countNominations(st, m));
        result.put("limit", effective);
        return result;
    }

    /**
     * 商家端：**发给我的**提名。{@code status} 省略 ⇒ 全部（页面自己分「待处理 / 已处理」）。
     * ⚠️ {@code merchantId} 是**调用者的号**，由 Controller 过 {@code IdentityGuard} 之后再传进来
     * —— 本方法不判身份（那是 Web 层的事，全仓同款）。
     *
     * @param onlyOpen {@code true} ⇒ 只回**规则还没到期**的行（店铺主页「平台活动」tab 用）。
     *                 缺省 / {@code false} ⇒ 与加这个参数之前**逐字节同行为**。
     *                 ⚠️ 判据落在 SQL 的 {@code NOW()} 上、**不是在 Java 里比时刻** ——
     *                 库里存 UTC、JVM 是本地钟，两边差 8 小时（{@code SubsidyDao.NOT_EXPIRED}
     *                 的注释把这条教训记全了）。也**不是**在 Controller 层过滤：那同样要读时刻。
     */
    public Map<String, Object> invitations(String merchantId, String status, Integer limit, Boolean onlyOpen) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        String st = normalizeNominationStatus(status);
        if (st == null && trimToNull(status) != null) {
            return fail("Status 只认 pending / approved / rejected");
        }
        int effective = normalizeLimit(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", subsidyDao.selectInvitations(merchantId.trim(), st, effective,
                Boolean.TRUE.equals(onlyOpen)));
        return result;
    }

    /**
     * 商家确认 / 拒绝一条提名。
     *
     * <p>成功回体带 {@code outcome}（{@code confirmed} / {@code rejected} / {@code already-handled}）
     * 与 {@code GoodName} / {@code MerchantId} —— Controller 据此给**管理端共享收件箱**回一条通知；
     * ⚠️ {@code already-handled} 时**不发通知**（那件事早就通知过了，重发就是两条同样的信）。
     *
     * @param approve {@code true} = 同意；{@code false} = 拒绝（此时 {@code reason} 必填）
     */
    public Map<String, Object> confirm(String merchantId, Long id, Boolean approve, String reason) {
        if (isBlank(merchantId) || id == null || id <= 0) {
            return fail("缺少参数：MerchantId / id");
        }
        boolean yes = Boolean.TRUE.equals(approve);
        String r = reason == null ? "" : reason.trim();
        // ⚠️ 拒绝必须给理由，且**一条 SQL 都不发** —— 校验在取快照之前（同提审制驳回那条）。
        if (!yes && r.isEmpty()) {
            return fail("拒绝时必须填写理由");
        }
        Map<String, Object> before = subsidyDao.selectNomination(id);
        if (before == null) {
            return fail("提名记录不存在");
        }
        if (!merchantId.trim().equals(str(before.get("MerchantId")))) {
            // 「不是你的提名」——**不是** 403（身份没错，是这条记录不属于你）：回业务失败，
            // 与全仓「失败即 200 + success:false」同形。
            return fail("这条提名不属于当前商家");
        }
        String target = yes ? STATUS_APPROVED : STATUS_REJECTED;
        int updated = subsidyDao.casConfirm(id, merchantId.trim(), target, yes ? "" : r);
        Map<String, Object> result = new HashMap<>();
        if (updated == 0) {
            // CAS 落空：这行已被处理过（或刚好被并发的另一次确认抢先）。**不是失败**。
            result.put("success", true);
            result.put("outcome", "already-handled");
            result.put("msg", "该提名已被处理过");
            result.put("Id", id);
            return result;
        }
        result.put("success", true);
        result.put("outcome", yes ? "confirmed" : "rejected");
        result.put("msg", yes ? "已确认参加国补" : "已拒绝");
        result.put("Id", id);
        result.put("GoodName", before.get("GoodName"));
        result.put("MerchantId", before.get("MerchantId"));
        result.put("Reason", r);
        return result;
    }

    /**
     * 用户端：国补商品列表（**已确认 + 规则在期 + 商品在架**），每行现算补贴额与到手价。
     *
     * @param goodType 非空 ⇒ 只回该类目
     */
    public Map<String, Object> subsidyGoods(String goodType, Integer limit) {
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = subsidyDao.selectSubsidyGoods(trimToNull(goodType), effective);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(withAmounts(row));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", out);
        result.put("total", out.size());
        result.put("limit", effective);
        return result;
    }

    /**
     * 给一行补贴商品算出 {@code SubsidyAmount} / {@code PayAmount}。
     *
     * <p>⚠️ 金额**只在这里算**，前端拿到的就是算好的两个数 —— 不让前端复刻一遍「封顶」这条规则
     * （复刻出来的第二份一定会在某次改动里跑偏）。
     * <p>⚠️ {@code Cap = 0} = **不封顶**（见类注释）；{@code Ratio} 超过 1 或价格非正 ⇒ 不补
     * （脏配置兜底，宁可显示成「无补贴」也不显示成「负价」）。
     */
    static Map<String, Object> withAmounts(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        Double price = toDouble(row.get("Price"));
        Double ratio = toDouble(row.get("Ratio"));
        Double cap = toDouble(row.get("Cap"));
        double amount = 0d;
        if (price != null && price > 0 && ratio != null && ratio > 0 && ratio <= 1.0) {
            amount = price * ratio;
            if (cap != null && cap > 0) {
                amount = Math.min(amount, cap);
            }
            if (amount > price) {
                amount = price; // 补贴不能把到手价压到负数
            }
        }
        amount = round2(amount);
        out.put("SubsidyAmount", amount);
        out.put("PayAmount", price == null ? null : round2(price - amount));
        return out;
    }

    // ==================== 内部 ====================

    private Map<String, Object> changeRuleStatus(String ruleId, String status, String okMsg) {
        if (isBlank(ruleId)) {
            return fail("缺少参数：RuleId");
        }
        Map<String, Object> before = subsidyDao.selectRule(ruleId);
        if (before == null) {
            return fail("规则不存在");
        }
        int updated = subsidyDao.updateRuleStatus(ruleId.trim(), status);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", updated == 0 ? "状态未变（已是目标状态）" : okMsg);
        result.put("updated", updated);
        result.put("before", before);
        result.put("RuleId", ruleId);
        return result;
    }

    /** 提名状态白名单：未知值 ⇒ {@code null}（由调用方区分「没给」与「给错了」）。 */
    private static String normalizeNominationStatus(String status) {
        String st = trimToNull(status);
        if (st == null) {
            return null;
        }
        return STATUS_PENDING.equals(st) || STATUS_APPROVED.equals(st) || STATUS_REJECTED.equals(st)
                ? st : null;
    }

    /** 规则业务号：{@code SB} + 4 个大写字母 + 8 位数字（留痕 {@code TargetId} 用，同 {@code CouponService.newCouponId}）。 */
    private static String newRuleId() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(14).append("SB");
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

    /**
     * 补率解析：既认 {@code "0.15"} 也认 {@code "15"}。
     * ⚠️ 判据是「**大于 1 就当百分数**」—— 不写「带 % 号才是百分数」（页面上那格是 number 输入，
     * 敲不出 % 号）。1 本身（=100%）两种读法同值，不歧义。
     */
    static Double parseRatio(String raw) {
        Double v = parseDouble(raw);
        if (v == null) {
            return null;
        }
        return v > 1.0 ? v / 100.0 : v;
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
