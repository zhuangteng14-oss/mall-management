package com.minke.mallmanagement.marketing.discount.service;

import com.minke.mallmanagement.marketing.discount.dao.DiscountTemplateDao;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 折扣模板服务（第三批㉖，2026-10-19）—— 模板列表 / 划线价预览 / 提审时的**服务端重算**。
 *
 * <h2>【前端提交什么、服务端认什么】</h2>
 * 前端提交 {@code DiscountTemplateId + Price}；{@code OriginalPrice} 一律由本服务按模板重算后**覆盖**，
 * 客户端传上来的原价不参与落库（防止「自己填一个 999 再卖 100」）。未选模板时
 * 保留老契约：手工 {@code OriginalPrice} 原样进入既有的 {@link DiscountLimitService} 校验。
 *
 * <h2>【两处 API】</h2>
 * <ul>
 *   <li>{@code /goods/discount/templates} —— 公开：商品编辑页的模板下拉；</li>
 *   <li>{@code /goods/discount/preview} —— 公开：选模板 / 改售价时实时预览反算结果。</li>
 * </ul>
 */
@Service
public class DiscountService {

    private final DiscountTemplateDao templateDao;

    public DiscountService(DiscountTemplateDao templateDao) {
        this.templateDao = templateDao;
    }

    /** 当前可用模板列表。 */
    public Map<String, Object> templates() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", templateDao.selectActive());
        return result;
    }

    /**
     * 预览：给定模板与售价，返回服务端反算的划线价。
     * 失败时 {@code success:false}，{@code valid:false}，文案直接给前端展示。
     */
    public Map<String, Object> preview(Long templateId, Double price, String goodType) {
        Map<String, Object> result = new HashMap<>();
        if (templateId == null || templateId <= 0) {
            result.put("success", false);
            result.put("valid", false);
            result.put("msg", "缺少参数：DiscountTemplateId");
            return result;
        }
        if (price == null || price <= 0) {
            result.put("success", false);
            result.put("valid", false);
            result.put("msg", "缺少参数：Price（须大于 0）");
            return result;
        }
        Map<String, Object> template = templateDao.selectById(templateId);
        if (template == null) {
            result.put("success", false);
            result.put("valid", false);
            result.put("msg", "折扣模板不存在或已停用");
            return result;
        }
        DiscountEngine.Result computed = DiscountEngine.compute(template, BigDecimal.valueOf(price), goodType);
        result.put("success", computed.valid());
        result.put("valid", computed.valid());
        result.put("templateId", templateId);
        result.put("templateCode", template.get("TemplateCode"));
        result.put("templateName", template.get("Name"));
        result.put("discountType", computed.discountType() != null ? computed.discountType() : template.get("DiscountType"));
        result.put("discountRate", computed.discountRate() != null ? computed.discountRate() : template.get("Rate"));
        result.put("discountAmount", computed.discountAmount());
        result.put("price", BigDecimal.valueOf(price));
        result.put("originalPrice", computed.originalPrice());
        result.put("maxMarkupRate", template.get("MaxMarkupRate"));
        if (!computed.valid()) {
            result.put("msg", computed.msg());
        } else {
            result.put("msg", "划线价已按模板重算");
        }
        return result;
    }

    /**
     * 提审时的服务端快照。返回的 {@code originalPrice} 是**唯一**允许落库的划线价。
     *
     * <p>未选模板（{@code templateId == null}）⇒ {@code valid=true} 且各快照字段为 {@code null}，
     * 调用方沿用客户端手工填写的原价（老契约）。
     */
    public SubmitSnapshot snapshotForSubmit(Long templateId, Double price, String goodType) {
        if (templateId == null || templateId <= 0) {
            return new SubmitSnapshot(true, null, null, null, null, null, null);
        }
        if (price == null || price <= 0) {
            return new SubmitSnapshot(false, "销售价必须大于 0", null, templateId, null, null, null);
        }
        Map<String, Object> template = templateDao.selectById(templateId);
        DiscountEngine.Result computed = DiscountEngine.compute(template,
                BigDecimal.valueOf(price), goodType);
        if (!computed.valid()) {
            return new SubmitSnapshot(false, computed.msg(), null, templateId, null, null, null);
        }
        return new SubmitSnapshot(true, null, computed.originalPrice(), templateId,
                computed.discountType(), computed.discountRate(), computed.discountAmount());
    }

    /** 记一条价格快照流水（失败不抛 —— 流水不是主流程的成败判据）。 */
    public void logPrice(Long goodsId, String batchId, String merchantId, String changeType,
                         BigDecimal price, BigDecimal originalPrice, SubmitSnapshot snapshot) {
        try {
            templateDao.insertPriceLog(goodsId, batchId, merchantId, changeType, price, originalPrice,
                    snapshot.templateId(), snapshot.discountType(), snapshot.discountRate(),
                    snapshot.discountAmount(),
                    "admin".equals(changeType) ? "admin" : "merchant",
                    merchantId == null ? "" : merchantId);
        } catch (Exception ignored) {
            // 价格流水是旁路证据：写失败不让提审 / 审核通过失败（审核通过 CAS 已消费，不可回退）。
        }
    }

    /**
     * 提审快照载体。
     *
     * @param originalPrice 服务端反算的划线价；{@code null} = 未选模板（沿用客户端值）
     */
    public record SubmitSnapshot(boolean valid, String msg, BigDecimal originalPrice,
                                 Long templateId, String discountType, BigDecimal discountRate,
                                 BigDecimal discountAmount) {
    }
}
