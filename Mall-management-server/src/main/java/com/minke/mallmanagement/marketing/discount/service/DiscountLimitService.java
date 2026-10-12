package com.minke.mallmanagement.marketing.discount.service;

import com.minke.mallmanagement.template.TemplateTypes;
import com.minke.mallmanagement.template.service.TemplateService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 折扣上下限校验（C3 块 5〈折扣〉，2026-10-12 用户 A 档批准）。
 *
 * <h2>【边界存在哪 —— 一行新表都不建】</h2>
 * 上限 / 下限按**类目**配，落在**通用模板表** {@code template} 上：
 * {@code Type = }{@link TemplateTypes#DISCOUNT_LIMIT} · {@code Name = }类目名
 * （对齐 {@code goodstype.GoodType}）· {@code Body = } {@code {"min":6.0,"max":9.5}}（单位：折）。
 * ⇒ 管理端的增删改**直接用现成的** {@code /admin/template/*} 三个端点，本笔**零新端点**。
 * 这也顺带保住了 {code template} 的既有口径：**改了配置不影响已上架商品**
 * （商品侧存的是折算好的 {@code goods.OriginalPrice} 数字，不是配置 Id）。
 *
 * <h2>【校验三条，判据写死在这儿】</h2>
 * <ol>
 *   <li>没填划线原价（{@code null} / {@code <= 0}）⇒ 通过。不填折扣是**正常状态**，
 *       全库 43 行的 {@code OriginalPrice} 都是 0（块 5 A 组加的列，值恒 0）。</li>
 *   <li>{@code 划线原价 <= 现价} ⇒ 拒。这不是"折扣"，是"涨价"。</li>
 *   <li>折数 = {@code 现价 / 划线原价 × 10}，须落在 {@code [min, max]}。
 *       {@code max} 恒有（默认 {@value #DEFAULT_MAX} 折 = 至少让利 5%）；{@code min} **只有配了才管**
 *       —— "类目没配上限"与"类目下限为 0"是两件事，不编造缺失的配置。</li>
 * </ol>
 *
 * <h2>【⚠️ 折的口径】</h2>
 * 中文「6.5 折」= 原价的 65%。故 {@code 折数 = 现价 / 划线原价 × 10}，**越小越便宜**。
 * 于是「不得低于 X 折」= {@code 折数 >= X}（5 折比 6.5 折更低），与用户原话一致。
 *
 * <p>⚠️ 本类是**纯函数式的规则判定**（无状态、不写库、不抛业务异常）—— 返回 {@code null} 表示通过，
 * 否则返回给用户看的**失败文案**。调用方（{@code GoodsService.goodsAdd}）把它塞进既有的
 * {@code {success:false, msg}} 通道，与那一路的所有早退同形。
 */
@Service
public class DiscountLimitService {

    /** 折数上限的兜底值：没给 {@code max} 时不许比这更"不划算"。9.5 折 = 至少让利 5%。 */
    public static final double DEFAULT_MAX = 9.5;

    /** 折数下限的兜底值 —— 只在**配置解析失败**时兜底，正常"没配"是**不限**（{@code null}）。 */
    public static final double FLOOR_MIN = 0.1;

    private static final Pattern NUMBER = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final TemplateService templateService;

    public DiscountLimitService(TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 校验一条上架请求的划线原价。
     *
     * @param goodType      本商品类目名（对齐 {@code goods.GoodType} / {@code template.Name}）
     * @param price         现价（{@code goods.Price}）
     * @param originalPrice 划线原价（{@code goods.OriginalPrice}），可空
     * @return {@code null} = 通过；否则为**失败文案**（直接进 {@code msg}）
     */
    public String validate(String goodType, Double price, Double originalPrice) {
        if (originalPrice == null || originalPrice <= 0) {
            return null; // 没填折扣 —— 通过（这是全库当前的常态）
        }
        if (price == null || price <= 0) {
            return null; // 现价本身有问题，归 Price 自己的规则管，不在这里连带报错
        }
        if (originalPrice <= price) {
            return "划线原价必须高于现价";
        }
        double rate = round2(price / originalPrice * 10.0);
        double min = limitOf(goodType, "min", null);
        double max = limitOf(goodType, "max", DEFAULT_MAX);
        if (rate > max) {
            return "该类目折扣不得高于 " + trim(max) + " 折（当前 " + trim(rate) + " 折）";
        }
        if (rate < min) {
            return "该类目折扣不得低于 " + trim(min) + " 折（当前 " + trim(rate) + " 折）";
        }
        return null;
    }

    /**
     * 读某类目某一条边界。**没配该边界 ⇒ {@code fallback}**（不是 0）——
     * "没配"与"配成 0"必须分开，否则一个漏配的类目会被判成"折扣不得低于 0 折"而全放行下限。
     */
    private double limitOf(String goodType, String key, Double fallback) {
        String body = bodyOf(goodType);
        if (body == null) {
            return fallback == null ? FLOOR_MIN : fallback;
        }
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                try {
                    return Double.parseDouble(m.group(2));
                } catch (NumberFormatException ignored) {
                    break; // 解析不出来 ⇒ 与"没配"同处置
                }
            }
        }
        return fallback == null ? FLOOR_MIN : fallback;
    }

    /** 取该类目的限制正文；类目名为空 / 没配 / 已停用 ⇒ {@code null}。 */
    private String bodyOf(String goodType) {
        if (goodType == null || goodType.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> result = templateService.list(TemplateTypes.DISCOUNT_LIMIT, null, false);
        Object raw = result.get("list");
        if (!(raw instanceof List<?> rows)) {
            return null;
        }
        String want = goodType.trim();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) {
                continue;
            }
            Object name = m.get("Name");
            if (name != null && want.equals(String.valueOf(name).trim())) {
                Object body = m.get("Body");
                return body == null ? null : String.valueOf(body);
            }
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 6.0 → "6"，6.5 → "6.5"（文案里不显多余的 .0）。 */
    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
