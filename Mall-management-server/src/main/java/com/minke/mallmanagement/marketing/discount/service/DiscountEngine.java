package com.minke.mallmanagement.marketing.discount.service;

import com.minke.mallmanagement.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 折扣模板的反算引擎（第三批㉖，2026-10-19）—— <b>纯函数</b>，不读库、不写库、不抛业务异常。
 *
 * <h2>【划线价反算：为什么必须服务器算】</h2>
 * 前端只提交 {@code 折扣模板 Id + 销售价}，**不提交划线原价**。原价由本类按模板反算：
 * <ul>
 *   <li><b>rate（折扣率）</b>：{@code O = ceil(P / R, 2)} —— 取满足 {@code O × R >= P} 的**最小**分位价。
 *       向上取整保证「按模板折扣算出来的价不高于售价」，同时 O 必然严格大于 P（R &lt; 1）。</li>
 *   <li><b>cut（直降）</b>：{@code O = P + CutAmount}。</li>
 * </ul>
 * 反算后再过两道闸：{@code O > P}（不得把涨价伪装成折扣）与
 * {@code O <= P × MaxMarkupRate}（不得把划线价吹到离谱的倍数）。
 *
 * <h2>【金额口径】</h2>
 * 全部 {@link BigDecimal} + {@link RoundingMode#CEILING} / {@code HALF_UP}，
 * **不出现 double 中间量**（第三批硬线）。返回给前端时由 Jackson 序列化为数字。
 *
 * <h2>【适用性】</h2>
 * 模板可按类目（{@code CategoryScope}，逗号分隔、空=全部）与价格区间
 * （{@code MinPrice} / {@code MaxPrice}，0=不限）限定适用面；不匹配 ⇒ {@code valid=false} + 文案。
 */
public final class DiscountEngine {

    private DiscountEngine() {
    }

    /**
     * 反算结果。
     *
     * @param valid          是否可用
     * @param msg            不可用时的用户文案（可用时为 {@code null}）
     * @param originalPrice  划线原价（2 位；不可用时为 {@code null}）
     * @param discountAmount 优惠额 {@code O - P}（2 位；不可用时为 {@code null}）
     * @param discountType   模板类型快照 {@code rate / cut}
     * @param discountRate   模板折扣率快照（cut 为 {@code null}）
     */
    public record Result(boolean valid, String msg, BigDecimal originalPrice,
                         BigDecimal discountAmount, String discountType, BigDecimal discountRate) {

        static Result fail(String msg) {
            return new Result(false, msg, null, null, null, null);
        }
    }

    /**
     * 按模板反算划线价。
     *
     * @param template {@code goods_discount_template} 的一行（键名与 SQL 投影一致）
     * @param price    销售价
     * @param category 商品类目名（可空；模板限定类目时空 ⇒ 不适用）
     */
    public static Result compute(Map<String, Object> template, BigDecimal price, String category) {
        if (template == null) {
            return Result.fail("折扣模板不存在或已停用");
        }
        if (price == null || price.signum() <= 0) {
            return Result.fail("销售价必须大于 0");
        }
        String status = str(template.get("Status"));
        if (!"active".equals(status)) {
            return Result.fail("折扣模板不存在或已停用");
        }

        String scopeError = checkScope(template, price, category);
        if (scopeError != null) {
            return Result.fail(scopeError);
        }

        String type = str(template.get("DiscountType"));
        BigDecimal rate = decimal(template.get("Rate"));
        BigDecimal cut = decimal(template.get("CutAmount"));
        BigDecimal maxMarkup = decimal(template.get("MaxMarkupRate"));
        if (maxMarkup.signum() <= 0) {
            maxMarkup = new BigDecimal("2.00");
        }

        BigDecimal original;
        BigDecimal snapshotRate;
        if ("rate".equals(type)) {
            if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                return Result.fail("折扣模板配置无效：折扣率须在 (0,1) 之间");
            }
            // ceil(P / R) —— 满足 O*R >= P 的最小分位价。
            original = Money.norm(price.divide(rate, Money.SCALE, RoundingMode.CEILING));
            snapshotRate = Money.normRate(rate);
        } else if ("cut".equals(type)) {
            if (cut.signum() <= 0) {
                return Result.fail("折扣模板配置无效：直降金额须大于 0");
            }
            original = Money.norm(price.add(cut));
            snapshotRate = null;
        } else {
            return Result.fail("折扣模板配置无效：未知折扣类型 " + type);
        }

        if (original.compareTo(price) <= 0) {
            return Result.fail("划线原价必须高于现价");
        }
        BigDecimal maxOriginal = Money.norm(price.multiply(maxMarkup));
        if (original.compareTo(maxOriginal) > 0) {
            return Result.fail("算出的划线价 " + original.toPlainString()
                    + " 超过模板允许的最高倍数（现价 × " + maxMarkup.toPlainString() + "）");
        }

        return new Result(true, null, original, Money.norm(original.subtract(price)), type, snapshotRate);
    }

    /** 类目 / 价格区间适用性。返回 {@code null} = 适用。 */
    private static String checkScope(Map<String, Object> template, BigDecimal price, String category) {
        String scope = str(template.get("CategoryScope")).trim();
        if (!scope.isEmpty()) {
            String target = category == null ? "" : category.trim();
            boolean matched = false;
            for (String item : scope.split(",")) {
                if (item.trim().equals(target) && !target.isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return "该折扣模板不适用于类目「" + target + "」";
            }
        }
        BigDecimal min = decimal(template.get("MinPrice"));
        BigDecimal max = decimal(template.get("MaxPrice"));
        if (min.signum() > 0 && price.compareTo(min) < 0) {
            return "该折扣模板仅适用于售价不低于 " + min.toPlainString() + " 元的商品";
        }
        if (max.signum() > 0 && price.compareTo(max) > 0) {
            return "该折扣模板仅适用于售价不高于 " + max.toPlainString() + " 元的商品";
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
